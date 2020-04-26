layout(triangles) in;
layout (triangle_strip, max_vertices=3) out;
 
 
in VertexData {
    vec2 texCoordV;
    vec3 Normal;
} VertexIn[3];
 
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
  for(int i = 0; i < gl_in.length(); i++)
  {
     // copy attributes
    gl_Position = gl_in[i].gl_Position;
    VertexOut.Normal = VertexIn[i].Normal;
    VertexOut.texCoordV = VertexIn[i].texCoordV;
    texCoordV=VertexIn[i].texCoordV;
    // done with the vertex
    EmitVertex();
  }
      EndPrimitive();
}
