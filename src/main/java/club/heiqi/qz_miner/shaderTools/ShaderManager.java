package club.heiqi.qz_miner.shaderTools;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

public class ShaderManager {
    public Logger LOG = LogManager.getLogger();
    public int shaderProgramID;
    public int vertexShaderID;
    public int fragmentShaderID;
    public int geometryShaderID;

    public Map<String, Integer> uniforms;
    public Map<String, Integer> attributes;

    /**标记着色器是否正在使用*/
    public boolean inUse = false;
    /**用于保存上一次使用的着色器*/
    public int lastShaderID = 0;

    public ShaderManager() {
        uniforms    = new HashMap<>();
        attributes  = new HashMap<>();
        initShaderProgram();
    }

    public void initShaderProgram() {
        shaderProgramID = GL20.glCreateProgram();
    }

    public ShaderManager loadShader(String vertexShaderSource, String fragmentShaderSource) {
        LOG.info("🚀开始加载着色器⚙");
        vertexShaderID = createShader(vertexShaderSource, GL20.GL_VERTEX_SHADER);
        fragmentShaderID = createShader(fragmentShaderSource, GL20.GL_FRAGMENT_SHADER);

        // 附加着色器到着色器程序
        GL20.glAttachShader(shaderProgramID, vertexShaderID);
        GL20.glAttachShader(shaderProgramID, fragmentShaderID);

        // 链接着色器程序
        linkAndValidate();

        // 清理
        GL20.glDeleteShader(vertexShaderID);
        GL20.glDeleteShader(fragmentShaderID);

        // 提示创建成功
        LOG.info("🚀着色器创建成功⚙");

        return this;
    }

    public int createShader(String source,int shaderType) {
        int shaderID = GL20.glCreateShader(shaderType);
        if (shaderID == 0) {
            throw new RuntimeException("创建着色器失败");
        }
        //  编译着色器
        GL20.glShaderSource(shaderID, source);
        GL20.glCompileShader(shaderID);

        if (GL20.glGetShaderi(shaderID, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            throw new RuntimeException("着色器编译失败");
        }

        return shaderID;
    }

    public void linkAndValidate() {
        GL20.glLinkProgram(shaderProgramID);
        if (GL20.glGetProgrami(shaderProgramID, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            throw new RuntimeException("着色器程序链接错误: " + GL20.glGetProgramInfoLog(shaderProgramID, 4096));
        }
        GL20.glValidateProgram(shaderProgramID);
        if (GL20.glGetProgrami(shaderProgramID, GL20.GL_VALIDATE_STATUS) == GL11.GL_FALSE) {
            throw new RuntimeException("着色器程序验证错误: " + GL20.glGetProgramInfoLog(shaderProgramID, 4096));
        }
    }



    public int getUniformLocation(String name) {
        // 先从缓存中获取，如果不存在则从OpenGL中获取
        if (uniforms.containsKey(name)) {
            return uniforms.get(name);
        }

        int location = GL20.glGetUniformLocation(shaderProgramID, name);
        if (location == -1) {
            throw new RuntimeException("Uniform 【" + name + "】不存在");
        }

        uniforms.put(name, location);

        return location;
    }

    public void setUniformI(String name, int value) {
        GL20.glUniform1i(getUniformLocation(name), value);
    }

    public void setUniformF(String name, float value) {
        GL20.glUniform1f(getUniformLocation(name), value);
    }

    public void setUniformM4f(String name, Matrix4f value) {
        FloatBuffer floatBuffer = BufferUtils.createFloatBuffer(16);
        floatBuffer.put(value.get(new float[16]));
        floatBuffer.flip();
        GL20.glUniformMatrix4(getUniformLocation(name), false, floatBuffer);
    }


    public void bind() {
        if (inUse)
            return;

        lastShaderID = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL20.glUseProgram(shaderProgramID);
        inUse = true;
    }

    public void unbind() {
        if (!inUse)
            return;

        GL20.glUseProgram(lastShaderID);
        inUse = false;
    }
}
