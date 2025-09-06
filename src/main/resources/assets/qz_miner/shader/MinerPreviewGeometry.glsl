#version 330

layout (lines) in;
layout (triangle_strip, max_vertices = 4) out;

in float[] vDistance;
in vec4[] vViewPosition;

uniform mat4 projection;
uniform float minWidth = 0.01;  // 最小线宽阈值
uniform float maxWidth = 5.0;   // 最大线宽阈值

void main()
{
    // 计算基础线宽（带透视效果）
    float baseWidth0 = 0.1 / vDistance[0];
    float baseWidth1 = 0.1 / vDistance[1];

    // 应用线宽阈值限制
    float width0 = clamp(baseWidth0, minWidth, maxWidth);
    float width1 = clamp(baseWidth1, minWidth, maxWidth);

    // 计算线段方向
    vec3 dir = normalize(vViewPosition[1].xyz - vViewPosition[0].xyz);

    // 计算垂直于线段和视线方向的向量
    vec3 perp = cross(dir, vec3(0, 0, 1));
    if (length(perp) < 0.0001)
    perp = vec3(1, 0, 0);  // 处理平行于Z轴的情况
    else
    perp = normalize(perp);

    // 计算偏移量
    vec3 offset0 = perp * width0 * 0.5;
    vec3 offset1 = perp * width1 * 0.5;

    // 生成四边形
    gl_Position = projection * (vViewPosition[0] - vec4(offset0, 0.0));
    EmitVertex();

    gl_Position = projection * (vViewPosition[1] - vec4(offset1, 0.0));
    EmitVertex();

    gl_Position = projection * (vViewPosition[0] + vec4(offset0, 0.0));
    EmitVertex();

    gl_Position = projection * (vViewPosition[1] + vec4(offset1, 0.0));
    EmitVertex();

    EndPrimitive();
}