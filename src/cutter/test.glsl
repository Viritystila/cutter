out vec4 op;
void main(void) {
  vec2 uv = (gl_FragCoord.xy / iResolution.xy);
  uv.y=1.0-uv.y;
  //uv.x = uv.x + 5.5*sin(0.015*iGlobalTime);
  //uv.y = uv.y + 2.5*cos(0.03*iGlobalTime);
  float data1_0=iDataArray1[0];
  float data1_1=iDataArray1[1];
  float data2_0=iDataArray2[0];
  uv=floor(uv * (1 + data1_0)) / ( 1 + data1_0);


  vec4 iChannel1_texture=texture2D(iChannel1, uv);
  vec4 iChannel2_texture=texture2D(iChannel2, uv);
  vec4 iChannel3_texture=texture2D(iChannel3, uv);
  vec4 iChannel4_texture=texture2D(iChannel4, uv);
  vec4 iChannel5_texture=texture2D(iChannel5, uv);
  vec4 iChannel6_texture=texture2D(iChannel6, uv);

  vec4 ich[6];
  ich[0]=iChannel1_texture;
  ich[1]=iChannel2_texture;
  ich[2]=iChannel3_texture;
  ich[3]=iChannel4_texture;
  ich[4]=iChannel5_texture;
  ich[5]=iChannel6_texture;

  int timefloor=min(int(floor( 6* (1+(sin(iGlobalTime*10.41))))), 5);
  //vec4 c1 = texture2D(iChannel0, uv);
  //vec4 c1b =texture2D(iChannel1, uv);
  //vec4 c1c =texture2D(iChannel2, uv);
  //vec4 c1d =texture2D(iChannel3, uv);

  //vec4 fftw=texture2D(iFftWave, uv);

  //vec4 c2 = texture2D(iCam0,uv2);
  //vec4 c3 = texture2D(iCam1,uv2);
  //vec4 c4 = texture2D(iCam2,uv2);
  //vec4 c5 = texture2D(iCam3,uv2);
  //vec4 c6 = texture2D(iCam4,uv2);

  //vec4 v1= texture2D(iVideo0, uv4);
  //vec4 v2= texture2D(iVideo1, uv4);
  //vec4 v3= texture2D(iVideo2, uv4);
  //vec4 v4= texture2D(iVideo3, uv4);
  //vec4 v5= texture2D(iVideo4, uv4);

  vec4 pf1=texture2D(iPreviousFrame, uv);
  vec4 text=texture2D(iText, uv);

  //vec4 c = mix(v1, v2,c2.r);  // alpha blend between two textures

  //vec4 cf = mix(c1,c2,sin(c3.r));  // alpha blend between two textures
  //vec4 cf1 = mix(cf,c1b,1.5-sin(c1.w));  // alpha blend between two textures
  //vec4 cf2 = mix(c,cf1,0.5-sin(c1.w)*iOvertoneVolume);  // alpha blend between two textures
  //vec4 cf3 = mix(cf2,c1d,0.01-sin(c1.w));  // alpha blend between two textures
  //vec4 cf4 = mix(v1,cf3,iOvertoneVolume);  // alpha blend between two textures
  //vec4 cf5 = mix(cf3,c2,sin(cf4.r));
  //vec4 cf6 = mix(c,c2, 0.5); //iDataArray[0]);
  vec4 ccc=vec4(cos(iGlobalTime*10.41)+data2_0, data1_0, sin(iGlobalTime*3.14+data1_1), 1);
  vec4 ppp=mix(iChannel2_texture, ccc, 0.5);
  //vec4 ooo=mix(iChannel1_texture, text, 0.5);
  //vec4 aaa=mix(vec4(cos(iGlobalTime*1.41)+data2_0, data1_0, sin(iGlobalTime*3.14+data1_1), 1), ooo, 0.5);
  float fade_size=2;
  float p1= mix(fade_size, 0.0-fade_size, uv.x-0.125);
  vec4 mixxx =mix(iChannel1_texture, ich[timefloor], smoothstep(1.0, 0.0, p1));
  op = ich[timefloor];//mixxx;//mix(text, ppp, cos(iGlobalTime*1.41)+data2_0);//ppp;//text;//iChannel1_texture;//iChannel1_texture;
  //op =mix(o, iChannel1_texture, 0.25);
  //out = op;
}
