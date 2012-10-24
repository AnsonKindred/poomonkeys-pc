import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.ListIterator;
import javax.media.opengl.GL;
import javax.media.opengl.GL2;
import javax.media.opengl.GLAutoDrawable;
import javax.media.opengl.GLCapabilities;
import javax.media.opengl.GLEventListener;
import javax.media.opengl.GLProfile;
import javax.media.opengl.awt.GLCanvas;
import javax.media.opengl.glu.GLU;

import poomonkeys.common.Drawable;
import poomonkeys.common.Geometry;
import poomonkeys.common.Matrix3x3;
import poomonkeys.common.Movable;
import poomonkeys.common.Renderer;
import poomonkeys.common.ShaderLoader;

import com.jogamp.opengl.util.FPSAnimator;

public class GLRenderer extends GLCanvas implements GLEventListener, Renderer
{
	
	private static final int MAX_INSTANCES = 100000;
	private static final int BATCH_SIZE    = 512;
	private static final int FLOAT_BYTES   = Float.SIZE / Byte.SIZE;
	
	// If fixedPipelineOnly is used then manuallyIndexVertices is always false and useTextureBuffer is ignored
	private boolean fixedPipelineOnly;
	
	// Include an element index as the z-component with each vertex
	// Used for pseudo-instancing shaders
	private static boolean manuallyIndexVertices; 
	
	// Batch position data into the texture buffer, this is the preferred option and allows for much larger batches than the alternate uniform array method
	// Both methods require shader support.
	private static boolean useTextureBuffer;
	
	public float viewWidth, viewHeight;
	public float screenWidth, screenHeight;

	private static GLRenderer instance = null;
	IntBuffer idBuffer = IntBuffer.allocate(1);
	
	PooMonkeysEngine engine;
	public long timeSinceLastDraw;
	private long lastDrawTime;
	
	private int currentlyBoundBuffer = 0;
	private int positionBufferID     = 0;
	
	private FPSAnimator animator;

	private ArrayList<Drawable> drawables          = new ArrayList<Drawable>();
	private ArrayList<Geometry> instanceGeometries = new ArrayList<Geometry>();
	private ArrayList<Movable[]> geometryInstances = new ArrayList<Movable[]>();
	// only used when uniform array position batching is used (no texture buffer available)
	private FloatBuffer positionBatchBuffer;
	
	private boolean didInit = false;
	
	// Shader attributes
	int defaultShaderProgram=-1, instancingShaderProgram;
	int projectionAttribute, vertexAttribute, positionAttribute, positionOffsetAttribute, mvpAttribute;

	public static GLRenderer getInstance()
	{
		if (instance == null)
			instance = new GLRenderer();
		return instance;
	}

	public GLRenderer()
	{
		// setup OpenGL Version 2
		super(new GLCapabilities(GLProfile.get(GLProfile.GL2)));

		this.addGLEventListener(this);
		this.setSize(1800, 1000);
		animator = new FPSAnimator(this, 60);
	}

	public void init(GLAutoDrawable d)
	{
		engine = PooMonkeysEngine.getInstance();
		
		final GL2 gl = d.getGL().getGL2();
		gl.glClearColor(0f, 0f, 0f, 1f);

		gl.glBlendFunc(GL2.GL_SRC_ALPHA, GL2.GL_ONE_MINUS_SRC_ALPHA);
		gl.glEnable(GL2.GL_BLEND);

		gl.glEnableClientState(GL2.GL_VERTEX_ARRAY);
		
		fixedPipelineOnly = !gl.isFunctionAvailable("glCreateShader");
		
		System.out.println("Shaders enabled: " + !fixedPipelineOnly);
		
		if(!fixedPipelineOnly)
		{
			manuallyIndexVertices = !gl.isFunctionAvailable("glDrawArraysInstanced");
			useTextureBuffer = gl.isFunctionAvailable("glTexBuffer");
			
			defaultShaderProgram = ShaderLoader.compileProgram(gl, "default");
			gl.glLinkProgram(defaultShaderProgram);
	        // Grab references to the shader attributes
	        mvpAttribute = gl.glGetUniformLocation(defaultShaderProgram, "mvp");
			
	        if(!manuallyIndexVertices)
	        {
	        	if(useTextureBuffer)
		        {
		        	instancingShaderProgram = ShaderLoader.compileProgram(gl, "instancing_texture");
		        }
	        	else
	        	{
	        		instancingShaderProgram = ShaderLoader.compileProgram(gl, "instancing_uniform");
	        	}
	        }
	        else
	        {
	        	if(useTextureBuffer)
		        {
		        	instancingShaderProgram = ShaderLoader.compileProgram(gl, "pseudo_instancing_texture");
		        }
	        	else
	        	{
	        		instancingShaderProgram = ShaderLoader.compileProgram(gl, "pseudo_instancing_uniform");
	        	}
	        }
	        gl.glLinkProgram(instancingShaderProgram);
	        
	        // Grab references to the shader attributes
	        projectionAttribute     = gl.glGetUniformLocation(instancingShaderProgram, "projection");
	        vertexAttribute         = gl.glGetAttribLocation(instancingShaderProgram, "vertex");
        	
	        if(useTextureBuffer)
	        {
	        	positionAttribute = gl.glGetUniformLocation(instancingShaderProgram, "positionSampler");
	        	positionOffsetAttribute = gl.glGetUniformLocation(instancingShaderProgram, "positionOffset");
	        }
	        else
	        {
	        	positionBatchBuffer = ByteBuffer.allocateDirect(BATCH_SIZE*2*FLOAT_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer();
	        	positionAttribute = gl.glGetUniformLocation(instancingShaderProgram, "positions");
	        }
	
	        gl.glUseProgram(instancingShaderProgram);
	        
	        if(useTextureBuffer)
	        {
	        	_preparePositionBuffer(gl);
	        }
		}
	}
	
	/**
	 * Set up a texture buffer to hold the position data and tell TEXTURE0 to use it.
	 * Also makes sure that the positionSampler is hooked up to TEXTURE0.
	 * 
	 * @param gl
	 */
	private void _preparePositionBuffer(GL2 gl)
	{
	    // Make sure the position sampler is bound to TEXTURE0 and TEXTURE0 is active
	    gl.glUniform1i(positionAttribute, 0); // 0 means TEXTURE0
	    gl.glActiveTexture(GL2.GL_TEXTURE0);
	    
		// Bind a texture buffer
		positionBufferID = _generateBufferID(gl);
		gl.glBindBuffer(GL2.GL_TEXTURE_BUFFER, positionBufferID);
	    
	    // Allocate some space
	    int size = MAX_INSTANCES * 2 * FLOAT_BYTES;
	    // Use STREAM_DRAW since the positions get updated very often 
	    gl.glBufferData(GL2.GL_TEXTURE_BUFFER, size, null, GL2.GL_STREAM_DRAW);
	    
	    // Unbind
	    gl.glBindBuffer(GL2.GL_TEXTURE_BUFFER, 0);

	    // The magic: Point the active texture (TEXTURE0) at the position texture buffer
	    // Right now the buffer is empty, but once we fill it, the positionSampler in
	    // the vertex shader will be able to access the data using texelFetch
	    gl.glTexBuffer(GL2.GL_TEXTURE_BUFFER, GL2.GL_RGBA32F, positionBufferID);
	}

	public void display(GLAutoDrawable d)
	{
		final GL2 gl = d.getGL().getGL2();
		timeSinceLastDraw = System.currentTimeMillis() - lastDrawTime;
		lastDrawTime = System.currentTimeMillis();
		
		gl.glClear(GL2.GL_COLOR_BUFFER_BIT | GL2.GL_DEPTH_BUFFER_BIT);

		synchronized(drawables)
		{
			if(!fixedPipelineOnly)
			{
				gl.glUseProgram(defaultShaderProgram);
			}
			ListIterator<Drawable> itr = drawables.listIterator();
			while(itr.hasNext())
			{
				Drawable drawable = itr.next();
				if(drawable.removeFromGLEngine)
				{
					itr.remove();
				}
				else
				{
					_drawDrawable(drawable, gl);
				}
			}
		}	

		synchronized(movableLock)
		{
			if(!fixedPipelineOnly)
			{
				gl.glUseProgram(instancingShaderProgram);
				if(useTextureBuffer)
				{
					_updatePositionBufferTexture(gl);
				}
			}
			int offset = 0;
			for(int g = 0; g < geometryInstances.size(); g++)
			{
				Geometry geometry = instanceGeometries.get(g);
				
				if(geometry.num_instances == 0)
				{
					continue;
				}
				
				if(geometry.hasChanged)
				{
					_compileGeometry(gl, geometry);
				}
				
				currentlyBoundBuffer = geometry.vertexBufferID;
		    	gl.glBindBuffer(GL.GL_ARRAY_BUFFER, currentlyBoundBuffer);
		    	
		    	if(!manuallyIndexVertices)
			    {
					gl.glVertexPointer(2, GL.GL_FLOAT, 0, 0);
			    }
				else 
				{
			    	// Pseudo instancing requires an element index stored in the z-component of each vertex, so 3 floats are required
					gl.glVertexPointer(3, GL.GL_FLOAT, 0, 0);
				}
				
				if(!fixedPipelineOnly && !useTextureBuffer)
				{
					// Using a uniform array for position data. Batching is required.
					Movable[] instances = geometryInstances.get(g);
					int b;
					for(b = 0; b < geometry.num_instances/BATCH_SIZE; b++)
					{
						_updatePositionBufferArray(gl, geometry, BATCH_SIZE, b*BATCH_SIZE);
						_drawInstances(gl, geometry, BATCH_SIZE);
					}
					
					// Get the remainder
					int num_remaining = geometry.num_instances - b*BATCH_SIZE;
					_updatePositionBufferArray(gl, geometry, num_remaining, b*BATCH_SIZE);
					_drawInstances(gl, geometry, num_remaining);
				}
				else if(!fixedPipelineOnly)
		    	{
					// Using the texture buffer, all the positons are already loaded and bound, just draw some instances
			    	gl.glUniform1i(positionOffsetAttribute, offset*2);
			    	_drawInstances(gl, geometry, geometry.num_instances);
		    	}
				else
				{
					// ewwwwww
					Movable[] instances = geometryInstances.get(g);
					for(int i = 0; i < geometry.num_instances; i++)
					{
						gl.glPushMatrix();
						gl.glTranslatef(instances[i].x, instances[i].y, 0);
						gl.glDrawArrays(GL2.GL_TRIANGLES, 0, geometry.vertices.length/2);
						gl.glPopMatrix();
					}
				}
		    	
		    	offset += geometry.num_instances;
			}
		}
	}
	
	private void _drawInstances(GL2 gl, Geometry g, int num_instances)
	{
		if(!manuallyIndexVertices)
		{
    		gl.glDrawArraysInstanced(GL2.GL_TRIANGLES, 0, g.vertices.length/2, num_instances);
		}
		else
		{
			gl.glDrawArrays(GL2.GL_TRIANGLES, 0, num_instances*g.vertices.length/2);
		}
	}
	
	private void _updatePositionBufferArray(GL2 gl, Geometry g, int batchSize, int batchOffset)
	{
		Movable[] instances = geometryInstances.get(g.geometryID);
		
		for(int i = 0; i < batchSize; i++)
		{
			positionBatchBuffer.put(instances[batchOffset+i].x);
			positionBatchBuffer.put(instances[batchOffset+i].y);
		}
		positionBatchBuffer.position(0);
		
		gl.glUniform1fv(positionAttribute, batchSize*2, positionBatchBuffer);
	}
	
	private void _updatePositionBufferTexture(GL2 gl)
	{
		gl.glBindBuffer(GL2.GL_TEXTURE_BUFFER, positionBufferID);
		ByteBuffer textureBuffer = gl.glMapBuffer(GL2.GL_TEXTURE_BUFFER, GL2.GL_WRITE_ONLY);
		FloatBuffer textureFloatBuffer = textureBuffer.order(ByteOrder.nativeOrder()).asFloatBuffer();
		
		for(int g = 0; g < geometryInstances.size(); g++)
		{
			Movable[] instances = geometryInstances.get(g);
			Geometry geometry = instanceGeometries.get(g);
			
			for(int i = 0; i < geometry.num_instances; i++)
			{
				textureFloatBuffer.put(instances[i].x);
				textureFloatBuffer.put(instances[i].y);
			}
		}
		
		gl.glUnmapBuffer(GL2.GL_TEXTURE_BUFFER);
	}
	
	private void _compileGeometry(GL2 gl, Geometry geometry)
	{
		geometry.buildGeometry(viewWidth, viewHeight);
	    
		if(!manuallyIndexVertices)
		{
			_finalizeGeometry(gl, geometry);
		}
		else
		{
			// Texture and uniform buffer methods require multiple instances of the geometry to be loaded
			_finalizeGeometry(gl, geometry, MAX_INSTANCES);
		}
		
		geometry.hasChanged = false;
	}
	
	private void _finalizeGeometry(GL2 gl, Geometry g)
	{
		g.hasChanged = false;
		
		if(g.vertices == null) return;
		
		int numBytes = g.vertices.length * FLOAT_BYTES;
        
        g.vertexBufferID = _generateBufferID(gl);
        
        currentlyBoundBuffer = g.vertexBufferID;
		gl.glBindBuffer(GL2.GL_ARRAY_BUFFER, currentlyBoundBuffer);
		
		gl.glBufferData(GL2.GL_ARRAY_BUFFER, numBytes, null, GL2.GL_STATIC_DRAW);
		
		ByteBuffer vertexBuffer = gl.glMapBuffer(GL2.GL_ARRAY_BUFFER, GL2.GL_WRITE_ONLY);
		FloatBuffer vertexFloatBuffer = vertexBuffer.order(ByteOrder.nativeOrder()).asFloatBuffer();
		
		vertexFloatBuffer.put(g.vertices);
		
		gl.glUnmapBuffer(GL2.GL_ARRAY_BUFFER);
		
		if(fixedPipelineOnly)
		{
			gl.glVertexPointer(2, GL.GL_FLOAT, 0, 0);
		}
		else
		{
			gl.glVertexAttribPointer(vertexAttribute, 2, GL.GL_FLOAT, false, 0, 0);
		}
	}
	
	private void _finalizeGeometry(GL2 gl, Geometry g, int numInstances)
	{
		g.hasChanged = false;
		
		if(g.vertices == null) return;
		

		int numBytes = 3*g.vertices.length*FLOAT_BYTES*MAX_INSTANCES/2;
        
		g.vertexBufferID = _generateBufferID(gl);
		
		currentlyBoundBuffer = g.vertexBufferID;
		gl.glBindBuffer(GL2.GL_ARRAY_BUFFER, currentlyBoundBuffer);
		
		gl.glBufferData(GL2.GL_ARRAY_BUFFER, numBytes, null, GL2.GL_STATIC_DRAW);
		
		ByteBuffer vertexBuffer = gl.glMapBuffer(GL2.GL_ARRAY_BUFFER, GL2.GL_WRITE_ONLY);
		FloatBuffer vertexFloatBuffer = vertexBuffer.order(ByteOrder.nativeOrder()).asFloatBuffer();
        
        // add the coordinates to the FloatBuffer
        for(int i = 0; i < numInstances; i++)
        {
        	for(int v = 0; v < g.vertices.length; v+=2)
            {
        		vertexFloatBuffer.put(g.vertices[v]);
        		vertexFloatBuffer.put(g.vertices[v+1]);
        		vertexFloatBuffer.put(i);
            }
        }
        
        gl.glUnmapBuffer(GL2.GL_ARRAY_BUFFER);
        
        // Texture and uniform buffer methods require an element index stored in the z-component of each vertex, so 3 floats are required
     	gl.glVertexAttribPointer(vertexAttribute, 3, GL.GL_FLOAT, false, 0, 0);
	}

	private void _drawDrawable(Drawable thing, GL2 gl)
	{
		
		if (!thing.didInit)
		{
			thing.init(viewWidth, viewHeight);
			
			/*if(thing.geometry != null && thing.geometry.needsCompile && thing.geometry.vertices != null)
			{
				thing.geometry.needsCompile = false;
			
				int bytesPerFloat = Float.SIZE / Byte.SIZE;
				
			    int numBytes = thing.geometry.vertices.length * bytesPerFloat;
			    
				IntBuffer vertexBufferID = IntBuffer.allocate(1);
				gl.glGenBuffers(1, vertexBufferID);
				thing.geometry.vertexBufferID = vertexBufferID.get(0);
				
				gl.glBindBuffer(GL2.GL_ARRAY_BUFFER, thing.geometry.vertexBufferID);
				gl.glBufferData(GL2.GL_ARRAY_BUFFER, numBytes, thing.geometry.vertexBuffer, GL2.GL_STATIC_DRAW);
				currentlyBoundBuffer = thing.geometry.vertexBufferID;
			}*/
		}
		
		if(fixedPipelineOnly)
		{
			gl.glPushMatrix();
			gl.glTranslatef(thing.p[0], thing.p[1], 0);
			if (thing.rotation != 0)
			{
				gl.glRotatef(thing.rotation, 0, 0, 1);
			}
			if (thing.scale.x != 1 || thing.scale.y != 1)
			{
				gl.glScalef(thing.scale.x, thing.scale.y, 0);
			}

		}
		else
		{
			Matrix3x3.push();
			Matrix3x3.translate(thing.p[0], thing.p[1]);

			// Rotation and scaling not implemented...lol
			if (thing.rotation != 0)
			{
				//gl.glRotatef(thing.rotation, 0, 0, 1);
			}
			if (thing.scale.x != 1 || thing.scale.y != 1)
			{
				//gl.glScalef(thing.scale.x, thing.scale.y, 0);
			}
	
			gl.glUniformMatrix3fv(mvpAttribute, 1, false, Matrix3x3.getMatrix());
		}
		
		if (thing.vertices != null)
		{
			_render(gl, thing.drawMode, thing);
		}
		if (thing.geometry != null && thing.geometry.vertices != null)
		{
			_render(gl, thing.geometry.drawMode, thing.geometry);
		}
		
		ListIterator<Drawable> itr = thing.drawables.listIterator();
		while(itr.hasNext())
		{
			Drawable drawable = itr.next();
			if(drawable.removeFromGLEngine)
			{
				itr.remove();
			}
			else
			{
				this._drawDrawable(drawable, gl);
			}
		}
		
		if(fixedPipelineOnly)
		{
			gl.glPopMatrix();
		}
		else
		{
			Matrix3x3.pop();
		}
	}
	
	public void _render(GL2 gl, int draw_mode, Geometry geometry)
	{
		if(geometry.vertexBufferID != currentlyBoundBuffer)
		{
			if(geometry.vertexBufferID == 0) return;
			
			gl.glBindBuffer(GL2.GL_ARRAY_BUFFER, geometry.vertexBufferID);
			currentlyBoundBuffer = geometry.vertexBufferID;

			gl.glVertexPointer(3, GL.GL_FLOAT, 0, 0);
		}
		gl.glDrawArrays(draw_mode, 0, geometry.getNumPoints());
	}

	public void _render(GL2 gl, int draw_mode, Drawable thing)
	{
		gl.glColor3f(1, 1, 1);
		if(thing.vertexBuffer == null) return;
		if(currentlyBoundBuffer != 0)
		{
			gl.glBindBuffer(GL2.GL_ARRAY_BUFFER, 0);
		}
		gl.glVertexPointer(3, GL.GL_FLOAT, 0, thing.vertexBuffer);
		gl.glDrawArrays(draw_mode, 0, thing.getNumPoints());
		currentlyBoundBuffer = 0;
	}
	
	private void _reshapeDrawables(ArrayList<Drawable> d)
	{
		ListIterator<Drawable> itr = d.listIterator();
		
		while(itr.hasNext())
		{
			Drawable drawable = itr.next();
			drawable.reshape(viewWidth, viewHeight);
			_reshapeDrawables(drawable.drawables);
		}
	}

	public void reshape(GLAutoDrawable d, int x, int y, int width, int height)
	{
		final GL2 gl = d.getGL().getGL2();
		gl.glViewport(0, 0, width, height);
		float ratio = (float) height / width;

		screenWidth = width;
		screenHeight = height;
		viewWidth = 100;
		viewHeight = viewWidth * ratio;
		
		if(!fixedPipelineOnly)
		{
			Matrix3x3.ortho(0, viewWidth, 0, viewHeight);
	
			gl.glUseProgram(instancingShaderProgram);
			// Send the projection matrix to the instancing shader, only needs to be sent once per resize
	        gl.glUniformMatrix3fv(projectionAttribute, 1, false, Matrix3x3.getMatrix());
			gl.glUseProgram(defaultShaderProgram);
		}
		else
		{
			gl.glMatrixMode(GL2.GL_PROJECTION);
			gl.glLoadIdentity();
			(new GLU()).gluOrtho2D(0, viewWidth, 0, viewHeight);
			
			gl.glMatrixMode(GL2.GL_MODELVIEW);
			gl.glLoadIdentity();
		}
        
		if (!didInit)
		{
			PooMonkeysEngine.getInstance().init();
			didInit = true;
		} 
		else
		{
			_reshapeDrawables(drawables);
		}
	}
	
	@Override
	public void addMovable(float x, float y, Geometry geom) 
	{
		synchronized(movableLock)
		{
			if(geom.geometryID == -1)
			{
				instanceGeometries.add(geom);
				geometryInstances.add(new Movable[MAX_MOVABLES]);
				geom.geometryID = instanceGeometries.size()-1;
			}
			
			Movable movable = new Movable();
			movable.x = x;
			movable.y = y;
			movable.geometryID = geom.geometryID; // geometry id
			
			geometryInstances.get(geom.geometryID)[geom.num_instances] = movable;
			geom.num_instances++;
		}
	}

	@Override
	public boolean removeMovable(int g, int i) 
	{
		synchronized(movableLock)
		{
			Movable[] instances = geometryInstances.get(g);
			Geometry geometry = instanceGeometries.get(g);
			
			if(geometry.num_instances <= i) 
			{
				System.out.println("Trying to delete past the end of list");
				return false;
			}
			
			geometry.num_instances--;
			instances[i] = instances[geometry.num_instances];
			instances[geometry.num_instances] = null;
			
			return true;
		}
	}
	
	@Override
	public int getGeometryID(Geometry geom) 
	{
		for(int i = 0; i < instanceGeometries.size(); i++)
		{
			if(instanceGeometries.get(i) == geom)
			{
				return i;
			}
		}
		return -1;
	}
	
	@Override
	public Geometry getGeometry(int id)
	{
		return instanceGeometries.get(id);
	}

	@Override
	public ArrayList<Movable[]> getMovables() 
	{
		synchronized(movableLock)
		{
			return geometryInstances;
		}
	}

	public void screenToViewCoords(float[] xy)
	{
		float viewX = (xy[0] / screenWidth) * viewWidth;
		float viewY = viewHeight - (xy[1] / screenHeight) * viewHeight;
		xy[0] = viewX;
		xy[1] = viewY;
	}

	@Override
	public void dispose(GLAutoDrawable drawable)
	{

	}

	@Override
	public long getTimeSinceLastDraw()
	{
		return timeSinceLastDraw;
	}
	
	public float getViewWidth()
	{
		return viewWidth;
	}
	
	public float getViewHeight()
	{
		return viewHeight;
	}
	
	public void start()
	{
		animator.start();
	}

	public void registerDrawable(Drawable d)
	{
		synchronized(drawables)
		{
			drawables.add(d);
		}
	}
	
	/**
	 * Generate an unused id for a buffer on the graphics card
	 * 
	 * @return the id
	 */
	private int _generateBufferID(GL2 gl)
	{
		gl.glGenBuffers(1, idBuffer);
		return idBuffer.get(0);
	}
}