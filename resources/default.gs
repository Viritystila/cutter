layout(triangles) in;
layout (triangle_strip, max_vertices=170) out;


in VertexData {
    vec4 vertexPosition_modelspace;
    vec3 colors_modelspace;
    vec3 index_modelspace;
    vec2 uv_modelspace;
    vec3 normals_modelspace;
    vec4 modelScale;
    vec4 modelPosition;
    mat4 modelRotation;
} VertexIn[];

out vec2 texCoordV;
out vec3 Normal;
out VertexData {
    vec2 texCoordV;
    vec3 Normal;
} VertexOut;



 void main()
{





    for(int i = 0; i < gl_in.length(); i++)
  {

    vec4 shift[2]=vec4[2](vec4(VertexIn[i].normals_modelspace,0),
                          vec4(VertexIn[i].normals_modelspace,0));

    vec4 sh=shift[iMeshID];
     // copy attributes
    gl_Position = gl_in[i].gl_Position;
    VertexOut.Normal = VertexIn[i].normals_modelspace;
    VertexOut.texCoordV = VertexIn[i].uv_modelspace;
    texCoordV=VertexIn[i].uv_modelspace;
    // done with the vertex
   EmitVertex();
  }
  EndPrimitive();



}
