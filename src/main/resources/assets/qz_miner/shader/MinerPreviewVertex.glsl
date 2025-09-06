#version 330

layout(location = 0) in vec3 position; // 顶点坐标

uniform mat4x4 model;
uniform mat4x4 view;
uniform mat4x4 projection;

out float vDistance;    // 顶点到相机的距离

void main() {
    gl_Position = projection * view * model * vec4(position, 1.0);
}