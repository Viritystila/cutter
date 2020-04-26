layout(triangles) in;
layout (triangle_strip, max_vertices=170) out;


in VertexData {
    vec2 texCoordV;
    vec3 Normal;
    vec4 vertexPos;
    int meshIdx;
} VertexIn[];

out VertexData {
    vec2 texCoordV;
    vec3 Normal;
} VertexOut;

out vec2 texCoordV;
out vec3 Normal;

 void main()
{
    //texCoordV=texCoordV;
    //Normal=Normal;
    vec4 shift[2]=vec4[2](vec4(0,0,0,0),vec4(0,0,0,0));

    for(int i = 0; i < gl_in.length(); i++)
  {
    vec4 sh=shift[VertexIn[i].meshIdx];
     // copy attributes
    gl_Position = gl_in[i].gl_Position+sh;
    VertexOut.Normal = VertexIn[i].Normal;
    VertexOut.texCoordV = VertexIn[i].texCoordV;
    texCoordV=VertexIn[i].texCoordV;
    // done with the vertex
   EmitVertex();
  }
  EndPrimitive();

 for (int k=0; k<0; k++){
  for(int i = 0; i < gl_in.length(); i++)
  {
     // copy attributes
    vec4 sh=shift[VertexIn[i].meshIdx];
    gl_Position = gl_in[i].gl_Position-sh+vec4(k, k, 0, 0);
    VertexOut.Normal = VertexIn[i].Normal;
    VertexOut.texCoordV = VertexIn[i].texCoordV;
    texCoordV=VertexIn[i].texCoordV;
    // done with the vertex
    EmitVertex();
  }
EndPrimitive();
}
//EndPrimitive();
}
