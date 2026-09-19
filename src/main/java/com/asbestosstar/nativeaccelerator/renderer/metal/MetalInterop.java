package com.asbestosstar.nativeaccelerator.renderer.metal;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
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
    private static final Map<String, Method> METHODS = new ConcurrentHashMap<>();
    private static final Map<String, Integer> INTS = new ConcurrentHashMap<>();
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
        return type("org.lwjgl.sdl." + simpleName);
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
        return constant(SDL_GPU, name);
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
        return call(object, method, value);
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

    private static Object invoke(Method method, Object receiver, Object[] args) {
        try {
            return method.invoke(receiver, adapt(method.getParameterTypes(), args));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access native binding " + method, e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Native binding failed: " + method, cause);
        }
    }

    private static Method resolve(Class<?> owner, String name, boolean isStatic, Object[] args) {
        String key = methodKey(owner, name, isStatic, args);
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
            return best;
        });
    }

    private static String methodKey(Class<?> owner, String name, boolean isStatic, Object[] args) {
        StringBuilder key = new StringBuilder(owner.getName()).append('#').append(name).append('#').append(isStatic);
        for (Object arg : args) key.append(':').append(arg == null ? "null" : arg.getClass().getName());
        return key.toString();
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
        Object[] adapted = args.clone();
        for (int i = 0; i < parameters.length; i++) {
            Object arg = adapted[i];
            if (!(arg instanceof Number number) || !isNumeric(parameters[i])) continue;
            Class<?> target = parameters[i].isPrimitive() ? parameters[i] : primitive(parameters[i]);
            if (target == byte.class) adapted[i] = number.byteValue();
            else if (target == short.class) adapted[i] = number.shortValue();
            else if (target == int.class) adapted[i] = number.intValue();
            else if (target == long.class) adapted[i] = number.longValue();
            else if (target == float.class) adapted[i] = number.floatValue();
            else if (target == double.class) adapted[i] = number.doubleValue();
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
