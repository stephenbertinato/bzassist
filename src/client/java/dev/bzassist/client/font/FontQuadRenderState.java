package dev.bzassist.client.font;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;

import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;

/** A batch of glyph quads submitted to the GUI render state. */
public record FontQuadRenderState(RenderPipeline pipeline, TextureSetup texture, Matrix3x2fc pose,
		float[] quads, int color, ScreenRectangle scissorArea, ScreenRectangle bounds)
		implements GuiElementRenderState {
	private static final int STRIDE = 8;

	@Override
	public void buildVertices(VertexConsumer consumer) {
		for (int i = 0; i < quads.length; i += STRIDE) {
			float x0 = quads[i];
			float y0 = quads[i + 1];
			float x1 = quads[i + 2];
			float y1 = quads[i + 3];
			float u0 = quads[i + 4];
			float v0 = quads[i + 5];
			float u1 = quads[i + 6];
			float v1 = quads[i + 7];

			vertex(consumer, x0, y0, u0, v0);
			vertex(consumer, x0, y1, u0, v1);
			vertex(consumer, x1, y1, u1, v1);
			vertex(consumer, x1, y0, u1, v0);
		}
	}

	private void vertex(VertexConsumer consumer, float x, float y, float u, float v) {
		consumer.addVertexWith2DPose(pose, x, y).setUv(u, v).setColor(color);
	}

	@Override
	public TextureSetup textureSetup() {
		return texture;
	}

	/** Snapshot of a pose stack so the state stays valid until the GUI is drawn. */
	public static Matrix3x2fc snapshot(Matrix3x2f stack) {
		return new Matrix3x2f(stack);
	}
}
