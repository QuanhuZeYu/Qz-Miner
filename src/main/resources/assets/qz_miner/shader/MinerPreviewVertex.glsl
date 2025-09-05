#version 330

layout(location = 0) in vec3 position; // 顶点坐标

uniform mat4x4 model;
uniform mat4x4 view;
uniform mat4x4 projection;

void main() {
    gl_Position = projection * view * model * vec4(position, 1.0);
}