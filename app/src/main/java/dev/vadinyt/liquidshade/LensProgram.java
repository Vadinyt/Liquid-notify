package dev.vadinyt.liquidshade;

/** Inputs are local device pixels; src is sampled in bitmap coordinates. */
final class LensProgram {
  static final String SOURCE =
      """
      uniform shader src;
      uniform float2 extent;
      uniform float2 center;
      uniform float3 mapX;
      uniform float3 mapY;
      uniform float3 mapW;
      uniform float2 frame;
      uniform float2 texture;
      uniform float2 radii;
      uniform float4 optic;
      uniform float4 finish;
      uniform float4 tone;
      uniform float4 accent;
      uniform float4 shade;
      uniform float4 wash;
      uniform float4 flags;
      uniform float4 detail;

      float dist(float2 p) {
          float r = p.y < 0.0 ? radii.x : radii.y;
          float2 q = abs(p) - extent + r;
          return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
      }
      half3 sampleAt(float2 screen, float spread) {
          float cover = max(frame.x/texture.x, frame.y/texture.y);
          float2 uv = (screen - 0.5*frame)/cover + 0.5*texture;
          float b = spread / cover;
          half3 c = src.eval(uv).rgb;
          if (b > 0.1) {
              c *= 0.28;
              c += 0.18*(src.eval(uv+float2(b,b)).rgb+src.eval(uv-float2(b,b)).rgb+
                  src.eval(uv+float2(b,-b)).rgb+src.eval(uv+float2(-b,b)).rgb);
          }
          return c;
      }
      float2 toBackdrop(float2 p) {
          float3 v = float3(p, 1);
          return float2(dot(mapX,v),dot(mapY,v))/max(0.001,dot(mapW,v));
      }
      half4 main(float2 point) {
          float2 p = point-center;
          float sd = dist(p);
          float inside = max(-sd,0.0);
          float2 grad = float2(dist(p+float2(0.6,0))-dist(p-float2(0.6,0)),
                               dist(p+float2(0,0.6))-dist(p-float2(0,0.6)));
          float2 normal = grad / max(length(grad),0.001);
          float band = clamp(1.0-inside/max(1.0,optic.y),0.0,1.0);
          float curve = band*band*(3.0-2.0*band);
          float2 offset = normal*optic.x*curve*(0.4+optic.z*0.6)*(1.0+detail.z*0.10);
          float2 xy = toBackdrop(point);
          half3 c = sampleAt(toBackdrop(point+offset),optic.w);
          if (finish.x > 0.01 && band > 0.001) {
              c.r = sampleAt(toBackdrop(point+offset+normal*band*finish.x),optic.w).r;
              c.b = sampleAt(toBackdrop(point+offset-normal*band*finish.x),optic.w).b;
          }
          float y = clamp(xy.y/frame.y,0.0,1.0);
          c = mix(c,half3(shade.rgb),shade.a);
          c *= 1.0-wash.x-wash.y*(1.0-y);
          float lum = dot(c,float3(0.2126,0.7152,0.0722));
          float chroma = max(max(c.r,c.g),c.b)-min(min(c.r,c.g),c.b);
          c = mix(half3(lum),c,tone.x + tone.y*(1.0-chroma));
          c = mix(c,half3(accent.rgb),tone.z);
          float lit = clamp(dot(normal,float2(-0.52,-0.85))*.5+.5,0.0,1.0);
          float rim = 1.0-smoothstep(0.0,max(0.15,finish.y),inside);
          float echo = exp(-abs(inside-optic.y*.66)/max(1.0,optic.y*.11))*curve;
          float sheen = finish.w*(0.018*(1.0-y)+echo*.16+curve*.10*lit);
          c = mix(c,half3(wash.z, wash.z, wash.z),detail.w);
          c = mix(c,half3(flags.rgb),clamp(rim*finish.z*(.18+.72*lit)+sheen,0.0,.85));
          float alpha = clamp(-sd+.5,0.0,1.0)*detail.x;
          if (detail.y > 0.0) alpha *= 1.0-smoothstep(1.0,detail.y,inside);
          return half4(clamp(c,0.0,1.0)*alpha,alpha);
      }
      """;
}
