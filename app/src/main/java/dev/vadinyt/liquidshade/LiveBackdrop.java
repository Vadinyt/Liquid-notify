package dev.vadinyt.liquidshade;

import android.graphics.*;
import java.lang.reflect.Method;

/** A bounded GPU filter of existing pixels in the current HWUI render target. No readback. */
final class LiveBackdrop {
  private static final Method SET_EFFECT = resolve();

  private static Method resolve() {
    try {
      return RenderNode.class.getMethod("setBackdropRenderEffect", RenderEffect.class);
    } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) {
      return null;
    }
  }

  static boolean supported() {
    return SET_EFFECT != null;
  }

  static final String SOURCE =
      """
      uniform shader scene;
      uniform float2 size;
      uniform float2 radii;
      uniform float4 optics; // displacement, bevel, compression, blur radius
      uniform float4 finish; // dispersion, saturation, vibrancy, foreground contrast polarity
      half4 main(float2 p) {
        float2 halfSize = size*.5;
        float2 u = p-halfSize;
        float r = u.y<0.0 ? radii.x : radii.y;
        float2 q=abs(u)-halfSize+r;
        float2 k=max(q,0.0);
        float l=length(k);
        float sd=l+min(max(q.x,q.y),0.0)-r;
        float2 n=sign(u)*(l>.001?k/max(l,.001):(q.x>q.y?float2(1,0):float2(0,1)));
        float band=1.0-smoothstep(0.0,max(optics.y,1.0),-sd);
        float angle=band*(1.03+.37*optics.z);
        float shift=optics.x*tan(angle-asin(sin(angle)/1.49))*(.66+.82*optics.z);
        // Pull scene details toward the glass edge, keeping taps inside the supplied tile.
        float2 pos=clamp(p-n*shift,float2(.5),size-.5);
        float radius=optics.w*(1.0-.8*band);
        half4 c=scene.eval(pos);
        if(radius>.25) {
          float2 a=float2(radius*.71,radius*.71);
          float2 b=float2(-a.x,a.y);
          c=.32*c+.17*(scene.eval(clamp(pos+a,float2(.5),size-.5))+
            scene.eval(clamp(pos-a,float2(.5),size-.5))+
            scene.eval(clamp(pos+b,float2(.5),size-.5))+
            scene.eval(clamp(pos-b,float2(.5),size-.5)));
        }
        if(band>.01 && finish.x>.01) {
          half4 red=scene.eval(clamp(pos+n*band*finish.x,float2(.5),size-.5));
          half4 blue=scene.eval(clamp(pos-n*band*finish.x,float2(.5),size-.5));
          c.r=mix(c.r,red.r/max(red.a,.001)*c.a,band*.7);
          c.b=mix(c.b,blue.b/max(blue.a,.001)*c.a,band*.7);
        }
        float y=dot(c.rgb,float3(.2126,.7152,.0722));
        float span=(max(max(c.r,c.g),c.b)-min(min(c.r,c.g),c.b))/max(c.a,.001);
        c.rgb=clamp(mix(half3(y),c.rgb,finish.y+finish.z*(1.0-span)),0.0,c.a);
        if(abs(finish.w)>.5) {
          float lum=dot(c.rgb,float3(.2126,.7152,.0722))/max(.001,c.a);
          float interior=1.0-.75*band;
          if(finish.w>0.0) {
            float excess=max(0.0,lum-.37);
            float roll=lum-excess+excess/(1.0+4.6*excess);
            c.rgb*=mix(1.0,roll/max(.001,lum),interior);
          } else {
            float deficit=max(0.0,.63-lum);
            float lift=deficit-deficit/(1.0+5.0*deficit);
            c.rgb=mix(c.rgb,half3(c.a),interior*lift/max(.001,1.0-lum));
          }
        }
        // A transparent render target contains no app pixels. Keep its compositor backdrop intact.
        return c*smoothstep(.08,.94,c.a);
      }
      """;

  private RenderNode node;
  private RuntimeShader lens;
  private boolean failed;
  private final float[] state = new float[12];
  private final Rect bounds = new Rect();
  private final Path shape = new Path();
  private final float[] corners = new float[8];

  boolean draw(
      Canvas canvas,
      RectF area,
      float top,
      float bottom,
      float shift,
      float bevel,
      float compression,
      float blur,
      float dispersion,
      float saturation,
      float vibrancy,
      float contrast,
      int alpha) {
    if (failed || SET_EFFECT == null || !canvas.isHardwareAccelerated() || area.isEmpty())
      return false;
    bounds.set(
        (int) Math.ceil(area.left),
        (int) Math.ceil(area.top),
        (int) Math.floor(area.right),
        (int) Math.floor(area.bottom));
    int w = bounds.width(), h = bounds.height();
    if (w < 2 || h < 2 || alpha != 255) return false;
    try {
      boolean fresh = node == null;
      if (fresh) {
        lens = new RuntimeShader(SOURCE);
        node = new RenderNode("LiquidShade live lens");
        node.setClipToBounds(true);
      }
      // Rendering a moving row can reuse its filter. Only geometry or material changes rebuild it.
      if (fresh
          || state[0] != w
          || state[1] != h
          || state[2] != top
          || state[3] != bottom
          || state[4] != shift
          || state[5] != bevel
          || state[6] != compression
          || state[7] != blur
          || state[8] != dispersion
          || state[9] != saturation
          || state[10] != vibrancy
          || state[11] != contrast) {
        lens.setFloatUniform("size", (float) w, (float) h);
        lens.setFloatUniform("radii", top, bottom);
        lens.setFloatUniform("optics", shift, bevel, compression, blur);
        lens.setFloatUniform("finish", dispersion, saturation, vibrancy, contrast);
        SET_EFFECT.invoke(node, RenderEffect.createRuntimeShaderEffect(lens, "scene"));
        state[0] = w;
        state[1] = h;
        state[2] = top;
        state[3] = bottom;
        state[4] = shift;
        state[5] = bevel;
        state[6] = compression;
        state[7] = blur;
        state[8] = dispersion;
        state[9] = saturation;
        state[10] = vibrancy;
        state[11] = contrast;
        corners[0] = corners[1] = corners[2] = corners[3] = top;
        corners[4] = corners[5] = corners[6] = corners[7] = bottom;
        shape.rewind();
        shape.addRoundRect(0, 0, w, h, corners, Path.Direction.CW);
        node.setPosition(0, 0, w, h);
        node.beginRecording(w, h);
        node.endRecording();
      }
      int save = canvas.save();
      try {
        canvas.translate(bounds.left, bounds.top);
        canvas.clipPath(shape);
        canvas.drawRenderNode(node);
      } finally {
        canvas.restoreToCount(save);
      }
      return true;
    } catch (ReflectiveOperationException | RuntimeException | LinkageError | OutOfMemoryError e) {
      failed = true;
      release();
      return false;
    }
  }

  void release() {
    if (node != null) node.discardDisplayList();
    node = null;
    lens = null;
  }
}
