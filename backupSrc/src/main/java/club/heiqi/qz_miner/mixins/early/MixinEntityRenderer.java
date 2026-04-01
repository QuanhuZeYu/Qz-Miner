package club.heiqi.qz_miner.mixins.early;

import club.heiqi.qz_miner.utils.MatrixUtils;
import net.minecraft.client.renderer.EntityRenderer;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public class MixinEntityRenderer {

    @Inject(method = "setupCameraTransform", at = @At("TAIL"), cancellable = false, remap = true)
    public void hookSetupCameraTransform_Tail(float p_78479_1_, int p_78479_2_, CallbackInfo callbackInfo) {

        MatrixUtils.floatBuffer.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MatrixUtils.floatBuffer);
        MatrixUtils.floatBuffer.rewind();
        MatrixUtils.modelView.set(MatrixUtils.floatBuffer);

        MatrixUtils.floatBuffer.clear();
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, MatrixUtils.floatBuffer);
        MatrixUtils.floatBuffer.rewind();
        MatrixUtils.projection.set(MatrixUtils.floatBuffer);
    }
}
