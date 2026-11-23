package dev.vadinyt.liquidshade;

final class LensProgram {
  static final String SOURCE =
      """
uniform shader src;
uniform shader clearSrc;
uniform float2 extent;
uniform float2 center;
uniform float3 mapX;
uniform float3 mapY;
uniform float3 mapW;
uniform float2 frame;
uniform float2 texture;
uniform float2 fullSize;
uniform float2 radii;
uniform float4 optic;
uniform float4 finish;
uniform float4 tone;
uniform float4 accent;
uniform float4 shade;
uniform float4 wash;
uniform float4 flags;
uniform float4 detail;
// xy = contact in normalised lens space, z = has real image, w = density in px/dp.
uniform float4 surface;
uniform float4 artUV;

float2 toBackdrop(float2 p) {
  if(surface.z>1.5)return p;
  float3 v=float3(p,1);
  return float2(dot(mapX,v),dot(mapY,v))/max(.001,dot(mapW,v));
}
float2 uvAt(float2 screen,float2 size) {
  if(surface.z>1.5)return screen*artUV.xy+artUV.zw;
  return (screen-frame*.5)/max(frame.x/size.x,frame.y/size.y)+size*.5;
}
half3 softAt(float2 screen) {
  float2 uv=uvAt(screen,texture);
  float b=surface.z>1.5?optic.w*.5*(artUV.x+artUV.y):optic.w/max(frame.x/texture.x,frame.y/texture.y);
  half3 c=src.eval(uv).rgb;
  if(b>.1) c=.36*c+.16*(src.eval(uv+float2(b,b)).rgb+src.eval(uv-float2(b,b)).rgb+
                                src.eval(uv+float2(b,-b)).rgb+src.eval(uv+float2(-b,b)).rgb);
  return c;
}
half4 main(float2 point) {
  float2 p=point-center;
  float r=p.y<0.0?radii.x:radii.y;
  float2 q=abs(p)-extent+r;
  float2 outer=max(q,0.0);
  float len=length(outer);
  float sd=len+min(max(q.x,q.y),0.0)-r;
  float aa=clamp(.5-sd,0.0,1.0);
  if(aa<=0.0) return half4(0);
  float2 n=(len>.001?outer/max(.001,len):(q.x>q.y?float2(1,0):float2(0,1)))*sign(p);
  float inset=max(0.0,-sd), bevel=max(1.0,optic.y);
  float band=clamp(1.0-inset/bevel,0.0,1.0);
  float slope=pow(band,1.65-.95*optic.z);
  float strength=optic.x*slope;
  float2 normp=p/max(extent,float2(1));
  float spring=detail.z;
  float2 contact=surface.xy;
  float locality=exp(-1.9*dot(normp-contact,normp-contact));
  float2 offset=n*strength*(.58+.9*optic.z)*(1.0+.32*spring*locality);
  float2 xy=toBackdrop(point);
  float y=clamp(xy.y/max(1.0,frame.y),0.0,1.0);
  float2 lamp=normalize(float2(-.62,-.84)+contact*min(.52,abs(spring)*.52));
  float facing=dot(n,lamp);
  float along=clamp(.5+.5*dot(normp,lamp),0.0,1.0);
  float crown=smoothstep(.15,.96,along);
  float rim=(1.0-smoothstep(0.0,max(.12,finish.y),inset))*finish.z;
  float ridge=exp(-abs(inset/bevel-(.32+.14*optic.z))*7.0)*slope;
  float spec=rim*(.07+.65*pow(abs(facing),3.0)+.19*crown);
  float reflection=finish.w*((.04+.18*crown)*slope+.34*ridge*max(-facing,0.0)+.033*(1.0-normp.y)*.5);
  float shine=clamp(spec+reflection+finish.w*.36*abs(spring)*locality*(.28+.72*band),0.0,.92);
  float shadow=clamp((.09*finish.w+.045*optic.z)*slope*(1.0-max(facing,0.0)),0.0,.25);
  float alpha=aa*detail.x;
  if(detail.y>0.0)alpha*=1.0-smoothstep(1.0,max(1.01,detail.y),inset);
  if(surface.z<.5) {
    // Premultiplied decoration over the real compositor output. No image sampling here.
    float tintA=tone.z;
    float3 tintRGB=mix(float3(dot(accent.rgb,float3(.2126,.7152,.0722))),accent.rgb,tone.x+tone.y*.35);
    float bodyA=tintA+(1.0-tintA)*detail.w;
    float3 body=tintRGB*tintA*(1.0-detail.w)+float3(wash.z)*detail.w;
    body*=1.0-shadow;bodyA+=shadow*(1.0-bodyA);
    float3 rgb=body*(1.0-shine)+flags.rgb*shine;
    float a=bodyA*(1.0-shine)+shine;
    return half4(clamp(rgb,0.0,a)*alpha,a*alpha);
  }
  float2 displaced=toBackdrop(point+offset);
  half3 c=softAt(displaced);
  if(flags.a>0.0 && band>.001) {
    float2 uv=uvAt(displaced,fullSize);
    half3 sharp=clearSrc.eval(uv).rgb;
    if(finish.x>.01) {
      sharp.r=clearSrc.eval(uvAt(toBackdrop(point+offset+n*band*finish.x),fullSize)).r;
      sharp.b=clearSrc.eval(uvAt(toBackdrop(point+offset-n*band*finish.x),fullSize)).b;
    }
    c=mix(c,sharp,smoothstep(0.0,.85,band)*flags.a);
  }
  if(surface.z<1.5)c=mix(c,half3(shade.rgb),shade.a)*(1.0-wash.x-wash.y*(1.0-y));
  float lum=dot(c,float3(.2126,.7152,.0722));
  float chroma=max(max(c.r,c.g),c.b)-min(min(c.r,c.g),c.b);
  c=mix(half3(lum),c,tone.x+tone.y*(1.0-chroma));
  c=mix(c,half3(accent.rgb),tone.z);
  c=mix(c,half3(wash.z),detail.w)*(1.0-shadow);
  c=mix(c,half3(flags.rgb),shine);
  return half4(clamp(c,0.0,1.0)*alpha,alpha);
}
""";
}
