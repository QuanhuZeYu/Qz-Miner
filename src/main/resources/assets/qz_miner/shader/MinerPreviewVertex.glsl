#version 330

layout(location = 0) in vec3 position; // 顶点坐标

uniform mat4x4 model;
uniform mat4x4 view;
uniform mat4x4 projection;
uniform vec3 cameraPos;

out float vDistance;    // 顶点到相机的距离
out vec4 vViewPosition; // 视图空间位置

void main() {
    vDistance = distance(position, cameraPos);
    vViewPosition = view * model * vec4(position, 1.0); // 计算视图空间位置
    gl_Position = projection * view * model * vec4(position, 1.0);
}