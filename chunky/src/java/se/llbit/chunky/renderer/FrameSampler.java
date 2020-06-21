package se.llbit.chunky.renderer;

import org.apache.commons.math3.util.FastMath;
import se.llbit.chunky.renderer.scene.Scene;
import se.llbit.chunky.resources.BitmapImage;
import se.llbit.math.ColorUtil;
import se.llbit.math.QuickMath;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.concurrent.BlockingQueue;

/**
 * A FrameSampler decides how samples are taken and reconstructs the image from them.
 */
public abstract class FrameSampler {
  public enum Type {
    /**
     * {@see UniformFrameSampler}
     */
    UNIFORM(1),

    /**
     * {@see AdaptiveFrameSampler}
     */
    ADAPTIVE(2);

    /**
     * This tag is used to determine the type of the sampler when (de)serializing from and to
     * data streams.
     */
    public final byte tag;

    private Type(int tag) {
      this.tag = (byte)tag;
    }

    /**
     * Returns the enum variant associated with the given tag.
     * @param tag The tag representing the type
     * @return The enum variant associated with the given tag
     * @throws IllegalArgumentException if an invalid tag was given
     */
    public static Type from(byte tag) throws IllegalArgumentException {
      switch (tag) {
        case 1:
          return UNIFORM;
        case 2:
          return ADAPTIVE;
        default:
          throw new IllegalArgumentException("Invalid frame sampler type: " + (int)tag);
      }
    }

    /**
     * Returns this type as a byte value.
     * @return a byte representing this type.
     */
    public byte asByte() {
      return this.tag;
    }
  }

  /**
   * Feed sample jobs to the workers for the current frame.
   * @param jobQueue
   */
  public abstract void sampleFrame(BlockingQueue<RenderTask> jobQueue) throws InterruptedException;

  /**
   * Called by the RenderManager once all render tasks for the current frame are finished.
   */
  public abstract void onFrameFinish();

  /**
   * Add a sample for pixel (x, y).
   * @param x pixel coordinate in image space
   * @param y pixel coordinate in image space
   * @param r radiance value in the red channel
   * @param g radiance value in the green channel
   * @param b radiance value in the blue channel
   * @param sampleCount the number of samples taken to reach the given radiance
   */
  public abstract void addSample(int x, int y, double r, double g, double b, int sampleCount);

  /**
   * Reconstructs pixel (x, y) from the samples and writes it to the output bitmap.
   * @param x pixel coordinate in image space
   * @param y pixel coordinate in image space
   * @param exposure amount of exposure for this pixel
   * @param postprocess postprocessing to apply to the pixel
   * @param output the output bitmap to write the final pixel value to
   */
  public final void finalizePixel(int x, int y, double exposure, Postprocess postprocess, BitmapImage output) {
    double[] pixel = new double[3];
    gatherRadiance(x, y, pixel);
    postprocess(pixel, exposure, postprocess);

    double clampedR = QuickMath.min(1, pixel[0]);
    double clampedG = QuickMath.min(1, pixel[1]);
    double clampedB = QuickMath.min(1, pixel[2]);

    int rgb32 = ColorUtil.getRGB(clampedR, clampedG, clampedB);
    output.setPixel(x, y, rgb32);
  }

  /**
   * Computes the radiance at pixel (x, y).
   * @param x pixel coordinate in image space
   * @param y pixel coordinate in image space
   * @param result rgb output for the computed radiance
   */
  protected abstract void gatherRadiance(int x, int y, double[] result);

  /**
   * Applies the given exposure and post-processing to the given pixel.
   * @param pixel the pixel to post-process
   * @param exposure amount of exposure to take into account
   * @param postprocess the kind of post-processing to apply
   */
  protected final void postprocess(double[] pixel, double exposure, Postprocess postprocess) {
    double r = pixel[0] * exposure;
    double g = pixel[1] * exposure;
    double b = pixel[2] * exposure;

    switch (postprocess) {
      case NONE:
        break;
      case TONEMAP1:
        // http://filmicgames.com/archives/75
        r = QuickMath.max(0, r - 0.004);
        r = (r * (6.2 * r + .5)) / (r * (6.2 * r + 1.7) + 0.06);
        g = QuickMath.max(0, g - 0.004);
        g = (g * (6.2 * g + .5)) / (g * (6.2 * g + 1.7) + 0.06);
        b = QuickMath.max(0, b - 0.004);
        b = (b * (6.2 * b + .5)) / (b * (6.2 * b + 1.7) + 0.06);
        break;
      case TONEMAP2:
        // https://knarkowicz.wordpress.com/2016/01/06/aces-filmic-tone-mapping-curve/
        float aces_a = 2.51f;
        float aces_b = 0.03f;
        float aces_c = 2.43f;
        float aces_d = 0.59f;
        float aces_e = 0.14f;
        r = QuickMath.max(QuickMath.min((r * (aces_a * r + aces_b)) / (r * (aces_c * r + aces_d) + aces_e), 1), 0);
        g = QuickMath.max(QuickMath.min((g * (aces_a * g + aces_b)) / (g * (aces_c * g + aces_d) + aces_e), 1), 0);
        b = QuickMath.max(QuickMath.min((b * (aces_a * b + aces_b)) / (b * (aces_c * b + aces_d) + aces_e), 1), 0);
        break;
      case TONEMAP3:
        // http://filmicgames.com/archives/75
        float hA = 0.15f;
        float hB = 0.50f;
        float hC = 0.10f;
        float hD = 0.20f;
        float hE = 0.02f;
        float hF = 0.30f;
        // This adjusts the exposure by a factor of 16 so that the resulting exposure approximately matches the other
        // post-processing methods. Without this, the image would be very dark.
        r *= 16;
        g *= 16;
        b *= 16;
        r = ((r * (hA * r + hC * hB) + hD * hE) / (r * (hA * r + hB) + hD * hF)) - hE / hF;
        g = ((g * (hA * g + hC * hB) + hD * hE) / (g * (hA * g + hB) + hD * hF)) - hE / hF;
        b = ((b * (hA * b + hC * hB) + hD * hE) / (b * (hA * b + hB) + hD * hF)) - hE / hF;
        float hW = 11.2f;
        float whiteScale = 1.0f / (((hW * (hA * hW + hC * hB) + hD * hE) / (hW * (hA * hW + hB) + hD * hF)) - hE / hF);
        r *= whiteScale;
        g *= whiteScale;
        b *= whiteScale;
        break;
      case GAMMA:
        r = FastMath.pow(r, 1 / Scene.DEFAULT_GAMMA);
        g = FastMath.pow(g, 1 / Scene.DEFAULT_GAMMA);
        b = FastMath.pow(b, 1 / Scene.DEFAULT_GAMMA);
        break;
    }

    pixel[0] = r;
    pixel[1] = g;
    pixel[2] = b;
  }

  /**
   * Reads a frame sampler from the given input stream.
   * @param inStream the stream from which the frame sampler will be read
   * @return the frame sampler that was read from the input stream
   * @throws IOException
   */
  public static FrameSampler read(DataInput inStream) throws IOException {
    Type samplerType = Type.from(inStream.readByte());

    switch (samplerType) {
      case UNIFORM:
        return new UniformFrameSampler(inStream);
      case ADAPTIVE:
        throw new NotImplementedException();
      default:
        // if the tag is not a valid type, Type.from() will throw an exception, otherwise we're
        // guaranteed a valid Type, which means this default statement should never be reached.
        throw new RuntimeException("Reached unreachable statement");
    }
  }

  /**
   * Writes the frame sampler to the output stream (to dump it to file for instance).
   * @param outStream the output stream the frame sampler will be written to
   */
  public abstract void write(DataOutput outStream) throws IOException;

  /**
   * Merges the samples of the provided sampler.
   * @param otherSampler The sampler from which the samples must be merged
   * @throws IllegalArgumentException when {@code otherSampler} is of the wrong type or the wrong size
   */
  public abstract void mergeWith(FrameSampler otherSampler)
          throws IllegalArgumentException;
}