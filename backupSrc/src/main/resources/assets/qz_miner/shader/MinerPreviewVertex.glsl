#version 330

layout(location = 0) in vec3 position;

uniform mat4x4 model;
uniform mat4x4 view;
uniform mat4x4 projection;
uniform vec3 cameraPos;

out float vDistance;
out vec4 vViewPosition;

void main() {
    vDistance = distance(position, cameraPos);
    vViewPosition = view * model * vec4(position, 1.0);
    gl_Position = projection * view * model * vec4(position, 1.0);
}