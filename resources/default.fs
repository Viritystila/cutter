out vec4 op;

in vec2 texCoordV;
in vec3 Normal;
in VertexData {
    vec2 texCoordV;
    vec3 Normal;
} VertexIn[3];

vec4 colorRemoval(vec4 fg, vec4 bg, float th, float mod1, float r, float g, float b){
  vec3 color_diff = fg.rgb - vec3(r, g, b);
  float squared_distance = dot(color_diff, color_diff);
  if (squared_distance < (mod1*th))
   {
     fg = bg;
   }

  return fg;

}



vec4 colorToAlpha(vec4 fg,  float th, float mod1, float r, float g, float b){
  vec3 color_diff = fg.rgb - vec3(r, g, b);
  float squared_distance = dot(color_diff, color_diff);
  if (squared_distance < (mod1*th))
   {
     fg.a = 0;
   }

  return fg;

}

void main(void) {
  vec3 norm=normalize(Normal);
  vec2 uv = (gl_FragCoord.xy / iResolution.xy);
  uv.y=-1.0-uv.y;
  vec4 iChannel1_texture=texture2D(iChannel1, texCoordV);
  iChannel1_texture.a=0.001;
  vec4 iChannel2_texture=texture2D(iChannel2, texCoordV);
  vec4 iChannel2_texture_inv=texture2D(iChannel2, texCoordV);
  vec4 iChannel3_texture=texture2D(iChannel3, uv);

  vec4 trns=colorToAlpha(iChannel2_texture, 0.015, 1, 0.1,0.1,0.8);

  iChannel2_texture.a=1;
  iChannel1_texture.a=1;
  vec4 text=texture2D(iText, uv);
  vec4 array[4]=vec4[4](iChannel1_texture, trns, iChannel2_texture, trns);
  op=array[iMeshID];
  //op =mix(iChannel1_texture+iChannel3_texture, iChannel2_texture+text*100, 0.5);
}
