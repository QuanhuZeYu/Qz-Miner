package club.heiqi.qz_miner.shaderTools;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

import javax.annotation.Nullable;
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

    /** 用于复用矩阵缓冲区 */
    private final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);

    /** 静态变量记录当前绑定的着色器 */
    private static int currentShaderID = 0;

    public ShaderManager() {
        uniforms    = new HashMap<>();
        attributes  = new HashMap<>();
        initShaderProgram();
    }

    public void initShaderProgram() {
        shaderProgramID = GL20.glCreateProgram();
    }

    public ShaderManager loadShader(String vertexShaderSource, String fragmentShaderSource, @Nullable String geometrySource) {
        // 清除旧缓存
        uniforms.clear();
        attributes.clear();

        LOG.info("🚀开始加载着色器⚙");
        vertexShaderID = createShader(vertexShaderSource, GL20.GL_VERTEX_SHADER);
        fragmentShaderID = createShader(fragmentShaderSource, GL20.GL_FRAGMENT_SHADER);
        if (geometrySource != null)
            geometryShaderID = createShader(geometrySource, GL32.GL_GEOMETRY_SHADER);

        // 附加着色器到着色器程序
        GL20.glAttachShader(shaderProgramID, vertexShaderID);
        GL20.glAttachShader(shaderProgramID, fragmentShaderID);
        if (geometrySource != null)
            GL20.glAttachShader(shaderProgramID, geometryShaderID);

        // 链接着色器程序
        linkAndValidate();

        // 清理
        GL20.glDeleteShader(vertexShaderID);
        GL20.glDeleteShader(fragmentShaderID);
        if (geometrySource != null)
            GL20.glDeleteShader(geometryShaderID);

        // 提示创建成功
        LOG.info("🚀着色器创建成功⚙");

        return this;
    }

    public int createShader(String source, int shaderType) {
        int shaderID = GL20.glCreateShader(shaderType);
        if (shaderID == 0) {
            throw new RuntimeException("创建着色器失败");
        }

        // 编译着色器
        GL20.glShaderSource(shaderID, source);
        GL20.glCompileShader(shaderID);

        // 添加详细的错误日志
        if (GL20.glGetShaderi(shaderID, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shaderID, 4096);
            throw new RuntimeException("着色器编译失败:\n" + log);
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
            LOG.warn("Uniform 【{}】不存在", name);
            return -1; // 返回-1而不是抛出异常
        }

        uniforms.put(name, location);
        return location;
    }

    public void setUniformI(String name, int value) {
        int location = getUniformLocation(name);
        if (location != -1) {
            GL20.glUniform1i(location, value);
        }
    }

    public void setUniformF(String name, float value) {
        int location = getUniformLocation(name);
        if (location != -1) {
            GL20.glUniform1f(location, value);
        }
    }

    public void setUniform3F(String name, Vector3f value) {
        int location = getUniformLocation(name);
        if (location != -1) {
            GL20.glUniform3f(location, value.x, value.y, value.z);
        }
    }

    public void setUniformM4f(String name, Matrix4f value) {
        int location = getUniformLocation(name);
        if (location != -1) {
            matrixBuffer.clear();
            value.get(matrixBuffer);
            matrixBuffer.flip();
            GL20.glUniformMatrix4(location, false, matrixBuffer);
        }
    }

    public void bind() {
        // 避免不必要的状态切换
        if (shaderProgramID == currentShaderID) {
            return;
        }

        GL20.glUseProgram(shaderProgramID);
        currentShaderID = shaderProgramID;
    }

    public void unbind() {
        GL20.glUseProgram(0);
        currentShaderID = 0;
    }

    public void destroy() {
        unbind();
        if (shaderProgramID != 0) {
            GL20.glDeleteProgram(shaderProgramID);
            shaderProgramID = 0;
        }
        uniforms.clear();
        attributes.clear();
    }
}