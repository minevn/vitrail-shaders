package dev.vitrail.render;

import dev.vitrail.Vitrail;

import com.mojang.blaze3d.GpuDeviceLossException;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The text a resource pack rewrote the shader of, held out of the level's feature windows and drawn
 * by the game's own pipeline over the finished picture, once the chain's final has written it.
 * <p>
 * <strong>Such text is a HUD laid out of glyphs</strong>, and {@link EntityDraw#readTextOverrides}
 * is where it is recognised. Handed back to the game at its own moment, it still landed in the
 * game's target before the chain ran: at full opacity among the solid features, which the seed
 * brings into the pack's image, and faded among the translucent ones, which the feature layer
 * composes onto it. Either way it went through the deferred and composite stages, so the pack lit
 * it, fogged it and blended it with the frames before, and a minimap meant to be read pixel for
 * pixel came out dim, tinted by the light around the player and smeared as the camera turned. On
 * 26.3 the see-through half of it landed under the final instead and was not seen at all. Held
 * here, none of it reaches the chain.
 * <p>
 * <strong>The draws are replayed as the game recorded them, and both halves of a draw are still
 * good at the end of the level.</strong> The render type carries its pipeline, its textures, its
 * scissor and its transforms. The execute info carries ranges of the vertex and index buffers the
 * features were uploaded to, and the pool those come from hands nothing back before the frame ends:
 * what it lent this frame waits in {@code usedThisFrame} until {@code GpuBufferPool.endFrame}, behind
 * a fence after that, on both games. What the draw reads from {@code RenderSystem} instead, at the
 * moment it is drawn rather than when it was recorded, is the projection, the fog and the lights,
 * so those three are taken when the draw is held and posed again around its replay.
 * <p>
 * <strong>What it costs is the text in the world that such a resource pack also draws</strong>, a
 * sign or a name plate: it is drawn flat over the finished picture too, with none of the pack's
 * light or fog on it, and tested against the depth of the whole world as drawn, so the water in
 * front of it now hides it rather than tinting it. A resource pack that leaves the text shader to
 * the game is not touched by any of this, its text being served by the pack as before.
 * <p>
 * Only the level's two feature windows hold a draw. The hand's pass and the screen are drawn by the
 * game after the chain already, and inside the light's walk there is no picture to hold it for.
 */
public final class HeldText {

	private static final Supplier<String> LABEL = () -> "Vitrail held text";

	/** What was held this frame, in the order the game would have drawn it. */
	private static final List<Held> HELD = new ArrayList<>();

	/**
	 * Set once a replay threw, after which text goes back to the game's moment as it did before this
	 * class: one failure is said once, rather than once a frame for the rest of the session.
	 */
	private static boolean broken;

	private HeldText() {
	}

	/**
	 * One held draw, and what the game's state said when it would have been drawn.
	 */
	record Held(PreparedRenderType prepared, StagedVertexBuffer.ExecuteInfo info,
			GpuBufferSlice projection, ProjectionType projectionType, GpuBufferSlice fog,
			GpuBufferSlice lights) {

		/** Poses the three things the draw reads from {@code RenderSystem} as they were. */
		void pose() {
			RenderSystem.setProjectionMatrix(this.projection, this.projectionType);
			RenderSystem.setShaderFog(this.fog);
			RenderSystem.setShaderLights(this.lights);
		}
	}

	/**
	 * Holds one draw for {@link #draw}.
	 *
	 * @return whether it was held, false meaning the caller hands it back to the game
	 */
	static boolean hold(PreparedRenderType prepared, StagedVertexBuffer.ExecuteInfo info) {
		if (broken) {
			return false;
		}

		HELD.add(new Held(prepared, info, RenderSystem.getProjectionMatrixBuffer(),
				RenderSystem.getProjectionType(), RenderSystem.getShaderFog(),
				RenderSystem.getShaderLights()));

		return true;
	}

	/**
	 * Draws what was held onto the game's main target, over whatever the chain's final left there.
	 * Called once the chain has run, with no pass of the game's open.
	 */
	public static void draw() {
		if (HELD.isEmpty()) {
			return;
		}

		List<Held> held = List.copyOf(HELD);
		HELD.clear();

		GpuBufferSlice projection = RenderSystem.getProjectionMatrixBuffer();
		ProjectionType projectionType = RenderSystem.getProjectionType();
		GpuBufferSlice fog = RenderSystem.getShaderFog();
		GpuBufferSlice lights = RenderSystem.getShaderLights();
		try {
			GameRender.drawHeld(held, LABEL);
		} catch (GpuDeviceLossException e) {
			throw e;
		} catch (RuntimeException e) {
			broken = true;
			Vitrail.logger().error("Vitrail could not draw the text a resource pack rewrote the shader "
					+ "of over the finished picture, so the game draws it at its own moment again", e);
		} finally {
			RenderSystem.setProjectionMatrix(projection, projectionType);
			RenderSystem.setShaderFog(fog);
			RenderSystem.setShaderLights(lights);
		}
	}

	/**
	 * Drops whatever is still held. At the head of every level frame, so that a frame that threw
	 * before the end of its level cannot hand the next one ranges of buffers that have been lent out
	 * again since, and when the world is left.
	 */
	public static void clear() {
		HELD.clear();
	}
}
