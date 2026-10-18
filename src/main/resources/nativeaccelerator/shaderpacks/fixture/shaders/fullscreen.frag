#version 460

layout(set = 0, binding = 0) uniform sampler2D main_sampler;
layout(location = 0) in vec2 tex_coord;
layout(location = 0) out vec4 out_color;

void main() {
    vec4 color = texture(main_sampler, tex_coord);
    out_color = vec4(color.b, color.g, color.r, color.a);
}
