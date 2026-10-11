package com.asbestosstar.nativeaccelerator.renderer.metal;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small late-bound adapter around LWJGL's SDL3 GPU bindings.
 *
 * <p>Minecraft 26.3 already owns the SDL lifetime. Native Accelerator therefore does not ship a
 * second SDL JNI/native library and does not call SDL_Init/SDL_Quit. Keeping generated LWJGL SDL
 * struct classes behind this adapter also prevents the renderer package from becoming coupled to
 * minor generated-signature churn between LWJGL 3.4 snapshots.</p>
 */
final class MetalInterop {
    static final String SDL_GPU = "org.lwjgl.sdl.SDLGPU";
    static final String SDL_VIDEO = "org.lwjgl.sdl.SDLVideo";
    static final String SDL_ERROR = "org.lwjgl.sdl.SDLError";
    static final String SDL_PROPERTIES = "org.lwjgl.sdl.SDLProperties";

    private static final Map<String, Class<?>> CLASSES = new ConcurrentHashMap<>();
    /** Resolved binding plus its parameter types (Method.getParameterTypes() clones on every call). */
    private record Resolved(Method method, Class<?>[] parameterTypes) {}

    /**
     * Allocation-light cache key. The previous implementation built a String from every argument's
     * class name on every call, even on cache hits, which put dozens of string concatenations on
     * the per-render-pass and per-upload hot paths.
     */
    private static final class MethodKey {
        private final Class<?> owner;
        private final String name;
        private final boolean isStatic;
        private final Class<?>[] argTypes;
        private final int hash;

        MethodKey(Class<?> owner, String name, boolean isStatic, Object[] args) {
            this.owner = owner;
            this.name = name;
            this.isStatic = isStatic;
            Class<?>[] types = new Class<?>[args.length];
            int h = owner.hashCode();
            h = 31 * h + name.hashCode();
            h = 31 * h + (isStatic ? 1 : 0);
            for (int i = 0; i < args.length; i++) {
                Class<?> type = args[i] == null ? null : args[i].getClass();
                types[i] = type;
                h = 31 * h + (type == null ? 0 : type.hashCode());
            }
            this.argTypes = types;
            this.hash = h;
        }

        @Override public int hashCode() { return hash; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof MethodKey k)) return false;
            return hash == k.hash && owner == k.owner && isStatic == k.isStatic
                    && name.equals(k.name) && Arrays.equals(argTypes, k.argTypes);
        }
    }

    private static final Map<MethodKey, Resolved> METHODS = new ConcurrentHashMap<>();
    private static final Map<String, Integer> INTS = new ConcurrentHashMap<>();
    /** SDLGPU constants keyed by bare name: no owner#name concatenation on every lookup. */
    private static final Map<String, Integer> SDL_GPU_INTS = new ConcurrentHashMap<>();
    /** LWJGL SDL struct classes keyed by simple name: no "org.lwjgl.sdl." concatenation per calloc. */
    private static final Map<String, Class<?>> SDL_STRUCTS = new ConcurrentHashMap<>();
    private static final Map<String, String> STRINGS = new ConcurrentHashMap<>();

    private MetalInterop() {}

    static boolean classPresent(String name) {
        try {
            type(name);
            return true;
        } catch (RuntimeException missing) {
            return false;
        }
    }

    static Class<?> type(String name) {
        return CLASSES.computeIfAbsent(name, key -> {
            try {
                return Class.forName(key, true, Thread.currentThread().getContextClassLoader());
            } catch (ClassNotFoundException first) {
                try {
                    return Class.forName(key);
                } catch (ClassNotFoundException second) {
                    throw new IllegalStateException("Required runtime class is unavailable: " + key, second);
                }
            }
        });
    }

    static Class<?> sdlStructType(String simpleName) {
        Class<?> cached = SDL_STRUCTS.get(simpleName);
        if (cached != null) return cached;
        Class<?> resolved = type("org.lwjgl.sdl." + simpleName);
        SDL_STRUCTS.put(simpleName, resolved);
        return resolved;
    }

    static int constant(String owner, String name) {
        String key = owner + '#' + name;
        return INTS.computeIfAbsent(key, ignored -> {
            try {
                Field field = type(owner).getField(name);
                return field.getInt(null);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Missing native constant " + key, e);
            }
        });
    }

    static int sdl(String name) {
        Integer cached = SDL_GPU_INTS.get(name);
        if (cached != null) return cached;
        int value = constant(SDL_GPU, name);
        SDL_GPU_INTS.put(name, value);
        return value;
    }

    static String stringConstant(String owner, String name) {
        String key = owner + '#' + name;
        return STRINGS.computeIfAbsent(key, ignored -> {
            try {
                Field field = type(owner).getField(name);
                Object value = field.get(null);
                if (!(value instanceof String text)) throw new IllegalStateException("Native constant is not a String: " + key);
                return text;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Missing native string constant " + key, e);
            }
        });
    }

    static String sdlString(String name) {
        return stringConstant(SDL_GPU, name);
    }

    static Object propertiesCall(String method, Object... args) {
        return callStatic(type(SDL_PROPERTIES), method, args);
    }


    static Object calloc(String structSimpleName) {
        return callStatic(sdlStructType(structSimpleName), "calloc");
    }

    static Object calloc(String structSimpleName, int capacity) {
        return callStatic(sdlStructType(structSimpleName), "calloc", capacity);
    }


    static Object get(Object object, int index) {
        return call(object, "get", index);
    }

    static Object get(Object object, String method) {
        return call(object, method);
    }

    static Object set(Object object, String method, Object value) {
        try {
            return call(object, method, value);
        } catch (IllegalStateException missingInstanceSetter) {
            /*
             * Some LWJGL generated structs expose a read-only-looking instance accessor for a
             * count field and only provide the writable form as static n<field>(address, value).
             * Try that generated unsafe setter before declaring the binding unavailable.
             */
            try {
                long address = nativeAddress(object);
                return callStatic(object.getClass(), "n" + method, address, value);
            } catch (RuntimeException noRawSetter) {
                missingInstanceSetter.addSuppressed(noRawSetter);
                throw missingInstanceSetter;
            }
        }
    }

    static Object set(Object object, String method, Object a, Object b) {
        return call(object, method, a, b);
    }

    static void free(Object object) {
        if (object == null) return;
        try {
            call(object, "free");
        } catch (RuntimeException ignored) {
            // Stack-backed views and a few generated value wrappers intentionally do not own memory.
        }
    }

    static Object sdlCall(String method, Object... args) {
        return callStatic(type(SDL_GPU), method, args);
    }

    static Object videoCall(String method, Object... args) {
        return callStatic(type(SDL_VIDEO), method, args);
    }


    static long sdlLong(String method, Object... args) {
        Object value = sdlCall(method, args);
        return ((Number)value).longValue();
    }

    static boolean sdlBool(String method, Object... args) {
        Object value = sdlCall(method, args);
        return (Boolean)value;
    }

    static int sdlInt(String method, Object... args) {
        Object value = sdlCall(method, args);
        return ((Number)value).intValue();
    }

    /**
     * Maps an SDL GPU transfer buffer across LWJGL SDL binding variants.
     *
     * <p>LWJGL's safe Java binding adds a fourth {@code buffer_size} argument and returns a
     * {@link ByteBuffer}, while the generated raw C-shaped entry point is named
     * {@code nSDL_MapGPUTransferBuffer} and keeps SDL's three arguments. Do not reflectively call
     * {@code SDL_MapGPUTransferBuffer(device, transfer, cycle)}: that signature does not exist in
     * current LWJGL SDL bindings even though the underlying C function has three parameters.</p>
     */
    static ByteBuffer mapGpuTransferBuffer(long device, long transfer, boolean cycle, int bufferSize) {
        if (bufferSize < 0) throw new IllegalArgumentException("bufferSize");

        Object[] friendlyArgs = {device, transfer, cycle, (long)bufferSize};
        Resolved friendly;
        try {
            friendly = resolve(type(SDL_GPU), "SDL_MapGPUTransferBuffer", true, friendlyArgs);
        } catch (IllegalStateException noFriendlyBinding) {
            // Compatibility fallback for generated LWJGL snapshots that expose only the raw
            // pointer-shaped entry point. Keep this fallback isolated here so callers never have
            // to know whether the binding returns a ByteBuffer or a native address.
            Object raw = sdlCall("nSDL_MapGPUTransferBuffer", device, transfer, cycle);
            long address = ((Number)raw).longValue();
            if (address == 0L) {
                throw new IllegalStateException("SDL_MapGPUTransferBuffer failed: " + lastSdlError());
            }
            return MemoryUtil.memByteBuffer(address, bufferSize);
        }

        Object mapped = invoke(friendly, null, friendlyArgs);
        if (mapped == null) {
            throw new IllegalStateException("SDL_MapGPUTransferBuffer failed: " + lastSdlError());
        }
        if (mapped instanceof ByteBuffer buffer) {
            return buffer;
        }
        // Be tolerant of a binding variant that exposes the safe name but still returns a pointer.
        if (mapped instanceof Number number) {
            long address = number.longValue();
            if (address == 0L) {
                throw new IllegalStateException("SDL_MapGPUTransferBuffer failed: " + lastSdlError());
            }
            return MemoryUtil.memByteBuffer(address, bufferSize);
        }
        throw new IllegalStateException("Unexpected SDL_MapGPUTransferBuffer return type: "
                + mapped.getClass().getName());
    }

    /**
     * Begins an SDL GPU render pass across LWJGL SDL binding variants.
     *
     * <p>The C API has four parameters, including {@code num_color_targets}. Current LWJGL
     * exposes a Java-friendly overload with only three parameters because it derives that count
     * from {@code SDL_GPUColorTargetInfo.Buffer}. Older/generated snapshots may also expose only
     * the raw {@code nSDL_BeginGPURenderPass} entry point. Keep that signature churn isolated here
     * rather than making render code guess which generated shape is present.</p>
     */
    static long beginGpuRenderPass(long commandBuffer, Object colorTargetInfos, int colorTargetCount,
                                   Object depthStencilTargetInfo) {
        if (colorTargetCount < 0) throw new IllegalArgumentException("colorTargetCount");

        Object[] friendlyArgs = {commandBuffer, colorTargetInfos, depthStencilTargetInfo};
        try {
            Resolved friendly = resolve(type(SDL_GPU), "SDL_BeginGPURenderPass", true, friendlyArgs);
            Object value = invoke(friendly, null, friendlyArgs);
            return ((Number)value).longValue();
        } catch (IllegalStateException noFriendlyBinding) {
            // Raw LWJGL entry points preserve the original C signature and therefore still take
            // the explicit color-target count plus native struct addresses.
            long colorsAddress = nativeAddress(colorTargetInfos);
            long depthAddress = nativeAddress(depthStencilTargetInfo);
            Object value = sdlCall("nSDL_BeginGPURenderPass", commandBuffer, colorsAddress,
                    colorTargetCount, depthAddress);
            return ((Number)value).longValue();
        }
    }

    /** Return the address of an LWJGL Struct/Struct.Buffer, or NULL for a nullable argument. */
    private static long nativeAddress(Object value) {
        if (value == null) return 0L;
        Object address = call(value, "address");
        if (!(address instanceof Number number)) {
            throw new IllegalStateException("LWJGL native object has non-numeric address(): "
                    + value.getClass().getName());
        }
        return number.longValue();
    }

    static String lastSdlError() {
        try {
            Object value = callStatic(type(SDL_ERROR), "SDL_GetError");
            return value == null ? "unknown SDL error" : value.toString();
        } catch (RuntimeException e) {
            return "unknown SDL error (" + e.getClass().getSimpleName() + ')';
        }
    }

    static Object call(Object receiver, String name, Object... args) {
        if (receiver == null) throw new IllegalArgumentException("receiver");
        return invoke(resolve(receiver.getClass(), name, false, args), receiver, args);
    }

    static Object callStatic(Class<?> owner, String name, Object... args) {
        return invoke(resolve(owner, name, true, args), null, args);
    }

    private static Object invoke(Resolved resolved, Object receiver, Object[] args) {
        long perfStart = MetalPerfCounters.tic();
        Method method = resolved.method();
        try {
            return method.invoke(receiver, adapt(resolved.parameterTypes(), args));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access native binding " + method, e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Native binding failed: " + method, cause);
        } finally {
            MetalPerfCounters.interop(perfStart);
        }
    }

    private static Resolved resolve(Class<?> owner, String name, boolean isStatic, Object[] args) {
        MethodKey key = new MethodKey(owner, name, isStatic, args);
        Resolved hit = METHODS.get(key);
        if (hit != null) return hit;
        return METHODS.computeIfAbsent(key, ignored -> {
            Method best = null;
            int bestScore = Integer.MIN_VALUE;
            for (Method method : owner.getMethods()) {
                if (!method.getName().equals(name)) continue;
                if (Modifier.isStatic(method.getModifiers()) != isStatic) continue;
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length != args.length) continue;
                int score = compatibility(parameters, args);
                if (score > bestScore) {
                    best = method;
                    bestScore = score;
                }
            }
            if (best == null || bestScore < 0) {
                throw new IllegalStateException("No compatible native binding: " + owner.getName() + '.' + name
                        + Arrays.toString(Arrays.stream(args).map(v -> v == null ? null : v.getClass().getName()).toArray()));
            }
            return new Resolved(best, best.getParameterTypes());
        });
    }

    private static int compatibility(Class<?>[] parameters, Object[] args) {
        int score = 0;
        for (int i = 0; i < parameters.length; i++) {
            Class<?> parameter = parameters[i];
            Object arg = args[i];
            if (arg == null) {
                if (parameter.isPrimitive()) return -1;
                score += 1;
                continue;
            }
            Class<?> actual = arg.getClass();
            if (parameter == actual) {
                score += 10;
                continue;
            }
            if (parameter.isAssignableFrom(actual)) {
                score += 7;
                continue;
            }
            if (parameter.isPrimitive() && wrapper(parameter).isAssignableFrom(actual)) {
                score += 9;
                continue;
            }
            if (isNumeric(parameter) && arg instanceof Number) {
                score += 6;
                continue;
            }
            if (parameter == CharSequence.class && actual == String.class) {
                score += 8;
                continue;
            }
            if (parameter == ByteBuffer.class && ByteBuffer.class.isAssignableFrom(actual)) {
                score += 8;
                continue;
            }
            return -1;
        }
        return score;
    }

    private static Object[] adapt(Class<?>[] parameters, Object[] args) {
        // Only copy the argument array (and re-box) when a numeric argument really needs a
        // different primitive width; the common case passes args through untouched.
        Object[] adapted = args;
        for (int i = 0; i < parameters.length; i++) {
            if (!(args[i] instanceof Number number)) continue;
            Class<?> parameter = parameters[i];
            Class<?> target = parameter.isPrimitive() ? parameter : primitive(parameter);
            Object converted;
            if (target == int.class) { if (number instanceof Integer) continue; converted = number.intValue(); }
            else if (target == long.class) { if (number instanceof Long) continue; converted = number.longValue(); }
            else if (target == float.class) { if (number instanceof Float) continue; converted = number.floatValue(); }
            else if (target == double.class) { if (number instanceof Double) continue; converted = number.doubleValue(); }
            else if (target == short.class) { if (number instanceof Short) continue; converted = number.shortValue(); }
            else if (target == byte.class) { if (number instanceof Byte) continue; converted = number.byteValue(); }
            else continue;
            if (adapted == args) adapted = args.clone();
            adapted[i] = converted;
        }
        return adapted;
    }

    private static boolean isNumeric(Class<?> type) {
        Class<?> primitive = type.isPrimitive() ? type : primitive(type);
        return primitive == byte.class || primitive == short.class || primitive == int.class
                || primitive == long.class || primitive == float.class || primitive == double.class;
    }

    private static Class<?> primitive(Class<?> wrapper) {
        if (wrapper == Byte.class) return byte.class;
        if (wrapper == Short.class) return short.class;
        if (wrapper == Integer.class) return int.class;
        if (wrapper == Long.class) return long.class;
        if (wrapper == Float.class) return float.class;
        if (wrapper == Double.class) return double.class;
        return wrapper;
    }

    private static Class<?> wrapper(Class<?> primitive) {
        if (primitive == int.class) return Integer.class;
        if (primitive == long.class) return Long.class;
        if (primitive == boolean.class) return Boolean.class;
        if (primitive == float.class) return Float.class;
        if (primitive == double.class) return Double.class;
        if (primitive == short.class) return Short.class;
        if (primitive == byte.class) return Byte.class;
        if (primitive == char.class) return Character.class;
        return primitive;
    }
}

