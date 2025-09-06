#version 330

out vec4 gl_FragColor;

uniform vec3 fogColor = vec3(0.2);      // 迷雾颜色（通常为深色）
uniform float fogNear = 0;      // 迷雾起始距离
uniform float fogFar = 32;       // 迷雾结束距离

void main() {
    // 原始颜色（白色）
    vec4 objectColor = vec4(1.0, 1.0, 1.0, 1.0);

    // 计算深度（0.0为近平面，1.0为远平面）
    float depth = gl_FragCoord.z / gl_FragCoord.w;

    // 计算迷雾强度（0.0=无迷雾，1.0=完全迷雾）
    float fogIntensity = clamp((depth - fogNear) / (fogFar - fogNear), 0.0, 1.0);

    // 混合物体颜色和迷雾颜色
    vec3 finalColor = mix(objectColor.rgb, fogColor, fogIntensity);

    gl_FragColor = vec4(finalColor, objectColor.a);
}