in vec2 texCoordV;
out vec4 op;

void main(void) {
  float time = iGlobalTime + 20.0;
  mat3 rotma =mat3(cos(time),sin(time),-sin(time),cos(time), 1.0, 1.0, 1.0, 1.0, 1.0);
  vec2 uv = (gl_FragCoord.xy/ iResolution.xy);
  vec2 uv3 = uv;
  vec4 pf1=texture2D(iPreviousFrame, uv);
  uv.y=1.0-uv.y*1;
  //uv.x = uv.x + 5.5*sin(0.015*iGlobalTime);
  //uv.y = uv.y + 2.5*cos(0.03*iGlobalTime);
  float data1_0=iDataArray1[0];
  float data1_1=iDataArray1[1];
  float data2_0=iDataArray2[0];
  uv=floor(uv * (100+iRandom*iFloat2 )) / ( 100+iRandom*iFloat2 + data1_0);
  vec2 uv2=texCoordV;
  uv2.y=1.0-uv2.y;

  vec4 iChannel1_texture=texture2D(iChannel1, uv);
  vec4 iChannel2_texture=texture2D(iChannel2, uv);
  vec4 iChannel3_texture=texture2D(iChannel3, uv);
  vec4 iChannel4_texture=texture2D(iChannel4, uv);
  vec4 iChannel5_texture=texture2D(iChannel5, uv);
  vec4 iChannel6_texture=texture2D(iChannel6, uv);
  vec4 iChannel7_texture=texture2D(iChannel7, uv2);

  vec4 ich[6];
  ich[0]=iChannel1_texture;
  ich[1]=iChannel2_texture;
  ich[2]=iChannel3_texture;
  ich[3]=iChannel4_texture;
  ich[4]=iChannel5_texture;
  ich[5]=iChannel6_texture;

  int timefloor=min(int(floor( 6* (1+(sin(iGlobalTime*10.41))))), 5);

  //vec4 pf1=texture2D(iPreviousFrame, uv);
  vec4 text=texture2D(iText, uv);
  vec4 ccc=vec4(cos(iGlobalTime*10.41)+data2_0, data1_0, sin(iGlobalTime*3.14+data1_1), 1);
  vec4 ppp=mix(iChannel7_texture, pf1, 0.008999);
  float fade_size=2;
  float p1= mix(fade_size, 0.0-fade_size, uv.x-0.125);
  vec4 mixxx =mix(iChannel6_texture, ppp, smoothstep(1.0, 0.0+iFloat1, p1));
  op = mixxx;//mixxx;// ich[timefloor];//mixxx;//mix(text, ppp, cos(iGlobalTime*1.41)+data2_0);//ppp;//text;//iChannel1_texture;//iChannel1_texture;
}
