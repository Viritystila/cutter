out vec4 op;
in vec2 texCoordV;
in vec3 Normal;
void main(void) {
  vec3 norm=normalize(Normal);
  vec2 uv = (gl_FragCoord.xy / iResolution.xy);
  uv.y=-1.0-uv.y;

  vec4 iChannel1_texture=texture2D(iChannel1, texCoordV);
  iChannel1_texture.a=0.001;
  vec4 iChannel2_texture=texture2D(iChannel2, texCoordV);
  vec4 iChannel3_texture=texture2D(iChannel3, uv);
  iChannel2_texture.a=1.0;
  iChannel1_texture.a=1;
  vec4 text=texture2D(iText, uv);
  vec4 array[3]=vec4[3](iChannel3_texture, iChannel1_texture, iChannel2_texture);
  op=array[iMeshID-1];
  //op =mix(iChannel1_texture+iChannel3_texture, iChannel2_texture+text*100, 0.5);
}
