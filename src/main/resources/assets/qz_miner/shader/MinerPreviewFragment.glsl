#version 330

out vec4 FragColor;

uniform vec3    fogColor    = vec3(0.2);
uniform float   fogNear     = 0;
uniform float   fogFar      = 32;
uniform vec4    lineColor   = vec4(1.0);

void main() {
    float depth = gl_FragCoord.z / gl_FragCoord.w;

    float fogIntensity = clamp((depth - fogNear) / (fogFar - fogNear), 0.0, 1.0);

    vec3 finalColor = mix(lineColor.rgb, fogColor, fogIntensity);

    float alpha = 1.0 - fogIntensity;

    FragColor = vec4(finalColor, alpha);
}