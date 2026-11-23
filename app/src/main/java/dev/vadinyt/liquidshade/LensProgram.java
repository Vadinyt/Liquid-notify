package dev.vadinyt.liquidshade;

final class LensProgram {
  static final String SOURCE =
      """
uniform shader src;
uniform shader clearSrc;
uniform shader coarseSrc;
// xy coarse resolution, z variance blend. Direct album images use the small kernel.
uniform float3 lowPass;
uniform float2 face;
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
  if(lowPass.x>0.0) {
    if(lowPass.z>.001) c=mix(c,coarseSrc.eval(uvAt(screen,lowPass.xy)).rgb,lowPass.z);
    return c;
  }
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
  float2 normalBase=len>.001?outer/max(.001,len):(q.x>q.y?float2(1,0):float2(0,1));
  float sd=len+min(max(q.x,q.y),0.0)-r;
  // A superelliptic corner joins the straight edge with vanishing curvature.
  if(face.x>.5 && min(q.x,q.y)>0.0) {
    float m=max(q.x,q.y);
    float2 u=q/m;
    float norm=m*pow(dot(u*u,sqrt(u)),.4);
    float2 v=q/max(.001,norm);
    float2 grad=v*sqrt(v);
    float g=max(.001,length(grad));
    sd=(norm-r)/g;
    normalBase=grad/g;
  }
  float aa=clamp(.5-sd,0.0,1.0);
  if(aa<=0.0) return half4(0);
  float2 n=normalBase*sign(p);
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
  // Two finite light sources. The rim tapers along each side instead of illuminating a
  // whole horizontal stroke. Specular width is independent of the optical bevel width.
  float keyPos=normp.x+.57+.23*normp.y;
  float fillPos=normp.x-.64+.14*normp.y;
  float key=pow(max(facing,0.0),2.5)*exp(-1.65*keyPos*keyPos);
  float fill=pow(max(-facing,0.0),3.0)*exp(-2.3*fillPos*fillPos);
  float lipWidth=max(.32,finish.y*.82);
  float lip=1.0-smoothstep(0.0,lipWidth+.45,inset);
  float spec=finish.z*lip*(.055+.89*key+.52*fill);
  // Bounded inner bounce at the far shoulder; the rest of the bevel obtains its colour
  // from the refracted scene. Dark glass stays clear between the two specular arcs.
  float ridge=exp(-abs(inset-max(.7,bevel*.26))/max(.5,surface.w*.70));
  float reflection=finish.w*(ridge*(.07*key+.38*fill)+.012*(1.0-normp.y));
  float glint=exp(-abs(dot(fromTouch,float2(.84,-.55))-.14*spring)*4.8);
  float contactLight=abs(spring)*locality*((.10+.42*glint)*(.24+.76*band)+.14*ridge);
  float shine=clamp(spec+reflection+(.55*finish.w+.45*finish.z)*contactLight,0.0,.88);
  float shadow=.068*finish.w*band*band*(1.0-key);
  float coverage=body.y>0.0 && body.x>=0.0 && surface.z<1.5 ?
    smoothstep(body.x,body.x+body.y,xy.y) : 1.0;
  if(body.z>.5 && coverage<=0.0)return half4(0);
  float alpha=aa*detail.x;
  if(body.z>.5)alpha*=coverage;
  if(detail.y>0.0)alpha*=1.0-smoothstep(1.0,max(1.01,detail.y),inset);
  if(body.z>.5 && surface.z<.5) {
    float dim=clamp(wash.x+wash.y*(1.0-y),0.0,.85);
    float a=shade.a+(1.0-shade.a)*dim;
    return half4(shade.rgb*shade.a*(1.0-dim)*alpha,a*alpha);
  }
  half4 clearGlass=half4(0);
  if(surface.z<.5 || (coverage<1.0 && body.z<.5)) {
    // Premultiplied decoration over the real compositor output. No image sampling here.
    // Keep a directional reflection when Samsung supplies only a compositor backdrop.
    // Narrow specular arc over a broader, softer shoulder. The far lip sits in shade.
    float facingArc=.5+.5*facing;
    float fresnel=pow(sin(angle),3.0);
    // Refractive strength cannot move unseen compositor pixels. It can still shape
    // physically plausible grazing-light caustics on the available glass boundary.
    // Do not saturate at the default refraction value: the slider must remain responsive.
    float lensPower=clamp(optic.x/max(1.0,30.0*surface.w),0.0,1.0);
    float contour=pow(band,1.4+1.0*optic.z);
    float shoulder=finish.w*(.15+.85*lensPower)*fresnel*(.08+.64*key+.18*fill);
    float bouncePos=bevel*(.22+.13*(1.0-facingArc));
    float bounce=exp(-abs(inset-bouncePos)/max(.65,surface.w*(.35+.27*optic.z)));
    float caustic=finish.w*(.11+.44*lensPower)*contour*bounce*(fill+.32*key);
    // A thin inner highlight makes clear glass readable on a uniform dark shade.
    // The crest occupies only a few pixels and does not recreate an opaque gray bezel.
    float crestDepth=max(2.0*surface.w,bevel*.23);
    float crest=exp(-abs(inset-crestDepth)/max(1.0*surface.w,bevel*.14));
    float innerLight=finish.z*(.25+.20*lensPower)*crest*(.42+.80*key);
    shine=clamp(shine+shoulder+caustic+innerLight,0.0,.77);
    shadow+=finish.w*.052*band*(1.0-band)*pow(1.0-facingArc,2.0);
    float tintA=tone.z;
    float3 tintRGB=mix(float3(dot(accent.rgb,float3(.2126,.7152,.0722))),accent.rgb,tone.x+tone.y*.35);
    float bodyA=tintA+(1.0-tintA)*detail.w;
    float3 body=tintRGB*tintA*(1.0-detail.w)+float3(wash.z)*detail.w;
    body*=1.0-shadow;bodyA+=shadow*(1.0-bodyA);
    // Chromatic split is confined to the thin light-catching edge, not a coloured halo.
    float split=clamp(finish.x/max(surface.w,1.0),0.0,.4)*lip*max(key,fill);
    float3 rimColour=clamp(flags.rgb+float3(.07*split,0.0,-.09*split),0.0,1.0);
    float3 rgb=body*(1.0-shine)+rimColour*shine;
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
    float shoulder=smoothstep(.0,.78,band);
    c=mix(c,sharp,shoulder*flags.a*(.66+.18*abs(facing)));
  }
  if(surface.z<1.5)c=mix(c,half3(shade.rgb),shade.a)*(1.0-wash.x-wash.y*(1.0-y));
  float lum=dot(c,float3(.2126,.7152,.0722));
  float chroma=max(max(c.r,c.g),c.b)-min(min(c.r,c.g),c.b);
  c=mix(half3(lum),c,tone.x+tone.y*(1.0-chroma));
  half3 pigment=half3(accent.rgb);
  if(face.y>.5 && face.x>.5) pigment=mix(pigment,clamp(c+.14,0.0,1.0),.46);
  c=mix(c,pigment,tone.z);
  // Adapt the face to the light actually sampled under the text. Preserve hue and keep
  // a dark wallpaper dark without a uniform gray wash. The optical rim keeps its colour.
  if(tone.w>.5) {
    float l=dot(c,float3(.2126,.7152,.0722));
    float interior=1.0-.72*smoothstep(.0,.96,band);
    if(wash.z<.5) {
      float excess=max(0.0,l-.36);
      float target=l-excess+excess/(1.0+4.8*excess);
      c*=mix(1.0,target/max(.001,l),interior);
    } else {
      float deficit=max(.63-l,0.0);
      float lift=deficit-deficit/(1.0+5.0*deficit);
      c=mix(c,half3(1.0),interior*lift/max(.001,1.0-l));
    }
  } else c=mix(c,half3(wash.z),detail.w);
  c*=1.0-shadow;
  c=mix(c,half3(flags.rgb),shine);
  half4 imageGlass=half4(clamp(c,0.0,1.0)*alpha,alpha);
  return body.z<.5 ? mix(clearGlass,imageGlass,coverage) : imageGlass;
}
""";
}
