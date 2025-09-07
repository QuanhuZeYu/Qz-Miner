#version 330

out vec4 gl_FragColor;

uniform vec3    fogColor    = vec3(0.2);    // 迷雾颜色
uniform float   fogNear     = 0;            // 迷雾起始距离
uniform float   fogFar      = 32;           // 迷雾结束距离
uniform vec4    lineColor   = vec4(1.0);    // 线的颜色

void main() {
    // 计算深度（0.0为近平面，1.0为远平面）
    float depth = gl_FragCoord.z / gl_FragCoord.w;

    // 计算迷雾强度（0.0=无迷雾，1.0=完全迷雾）
    float fogIntensity = clamp((depth - fogNear) / (fogFar - fogNear), 0.0, 1.0);

    // 混合物体颜色和迷雾颜色
    vec3 finalColor = mix(lineColor.rgb, fogColor, fogIntensity);

    // 计算透明度：距离越远越透明（fogIntensity=1时完全透明）
    float alpha = 1.0 - fogIntensity;

    gl_FragColor = vec4(finalColor, alpha);
}