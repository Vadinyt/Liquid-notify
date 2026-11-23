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
// In panel-local pixels. The complete native header stays transparent.
uniform float3 body;

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
  float2 normp=p/max(extent,float2(1));
  float spring=detail.z;
  float2 contact=surface.xy;
  float2 fromTouch=normp-contact;
  float locality=exp(-1.9*dot(fromTouch,fromTouch));
  // Local yielding bends the contacted part of the outline. At rest the exact SDF returns.
  float2 shaped=p*(1.0+spring*locality*min(float2(.12),float2(surface.w*2.8)/max(extent,float2(1))));
  float r=shaped.y<0.0?radii.x:radii.y;
  float2 q=abs(shaped)-extent+r;
  float2 outer=max(q,0.0);
  float len=length(outer);
  float sd=len+min(max(q.x,q.y),0.0)-r;
  float aa=clamp(.5-sd,0.0,1.0);
  if(aa<=0.0) return half4(0);
  float2 n=(len>.001?outer/max(.001,len):(q.x>q.y?float2(1,0):float2(0,1)))*sign(p);
  float inset=max(0.0,-sd), bevel=max(1.0,optic.y);
  float band=clamp(1.0-inset/bevel,0.0,1.0);
  // Rounded lens shoulder. Snell displacement approaches the edge tangentially
  // and returns to zero with zero slope where it joins the flat face.
  float angle=band*band*(3.0-2.0*band)*(1.08+.33*optic.z);
  float bent=angle-asin(clamp(sin(angle)/1.49,-1.0,1.0));
  float slope=tan(bent);
  float strength=optic.x*slope;
  float2 offset=n*strength*(.58+.9*optic.z)*(1.0+.43*spring*locality);
  offset+=fromTouch*optic.x*.48*spring*locality*(1.0-band);
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
  float glint=exp(-abs(dot(fromTouch,float2(.84,-.55))-.14*spring)*4.8);
  float contactLight=abs(spring)*locality*((.10+.42*glint)*(.24+.76*band)+.14*ridge);
  float shine=clamp(spec+reflection+(.55*finish.w+.45*finish.z)*contactLight,0.0,.92);
  float shadow=clamp((.09*finish.w+.045*optic.z)*slope*(1.0-max(facing,0.0)),0.0,.25);
  float coverage=body.y>0.0 && body.x>=0.0 && surface.z<1.5 ?
    smoothstep(body.x,body.x+body.y,xy.y) : 1.0;
  if(body.z>.5 && coverage<=0.0)return half4(0);
  if(body.z<.5) {
    // A narrow reflected light below the bright lip changes sides around the rounded corners.
    float arch=exp(-abs(inset/bevel-.29)*8.5);
    float reverse=exp(-abs(inset/bevel-.68)*8.0);
    shine=clamp(shine+finish.w*(.38*arch*max(facing,.0)+.30*reverse*max(-facing,.0)),0.0,.92);
  }
  float alpha=aa*detail.x;
  if(body.z>.5)alpha*=coverage;
  if(detail.y>0.0)alpha*=1.0-smoothstep(1.0,max(1.01,detail.y),inset);
  half4 clearGlass=half4(0);
  if(surface.z<.5 || (coverage<1.0 && body.z<.5)) {
    // Premultiplied decoration over the real compositor output. No image sampling here.
    // Two bands on opposite sides of a curved bevel keep the relief legible on flat OEM blur.
    float width=max(bevel,1.0);
    float shoulder=exp(-abs(inset/width-.32)*5.0);
    float inner=exp(-abs(inset/width-.77)*7.0);
    float directional=.25+.75*abs(facing);
    float caustic=(.19*finish.z+.30*finish.w)*shoulder*directional
                 +.14*finish.w*inner*max(-facing,0.0);
    shine=clamp(shine+caustic,0.0,.92);
    shadow=clamp(shadow+.12*finish.w*inner*max(facing,0.0),0.0,.34);
    float tintA=tone.z;
    float3 tintRGB=mix(float3(dot(accent.rgb,float3(.2126,.7152,.0722))),accent.rgb,tone.x+tone.y*.35);
    float bodyA=tintA+(1.0-tintA)*detail.w;
    float3 body=tintRGB*tintA*(1.0-detail.w)+float3(wash.z)*detail.w;
    body*=1.0-shadow;bodyA+=shadow*(1.0-bodyA);
    float3 rgb=body*(1.0-shine)+flags.rgb*shine;
    float a=bodyA*(1.0-shine)+shine;
    clearGlass=half4(clamp(rgb,0.0,a)*alpha,a*alpha);
    if(surface.z<.5 || coverage<=0.0)return clearGlass;
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
  half4 imageGlass=half4(clamp(c,0.0,1.0)*alpha,alpha);
  return body.z<.5 ? mix(clearGlass,imageGlass,coverage) : imageGlass;
}
""";
}
