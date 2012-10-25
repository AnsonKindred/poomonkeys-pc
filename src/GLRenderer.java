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

/**
 * GLRenderer renders Geometry and handles all GLEvents.
 * There are, broadly, two rendering paths that can be used. 
 * Complex objects that are not drawn many times in a scene are rendered on an individual bases.
 * Simple objects that need to be drawn many times are rendered using specialized 'instance rendering' methods that are faster for this purpose.
 * 
 * 5 possible instance rendering modes are supported
 * The options will be configured automagically based on calls to isFunctionAvailable during initialization
 * but for reference they are listed below.
 * 
 * In order of rendering efficiency (more or less) they are:
 * 
 * 1. fixedPipelineOnly
 *      No shader support at all, uses the fixed pipeline and a drawArrays call for every instance drawn
 * 2. manuallyIndexVertices && !useTextureBuffer
 *      No glDrawArraysInstanced and no texture buffer.
 *      A VBO is used to store a batch of vertices.
 *      A uniform array is used to store batches of position data.
 * 3. manuallyIndexVertices && useTextureBuffer
 *      No glDrawArraysInstanced.
 *      A VBO is used to store a batch of vertices.
 *      The texture buffer is used to store position data.
 * 4. !manuallyIndexVertices && !useTextureBuffer
 *      glDrawArraysInstanced is available but the texture buffer is not.
 *      This probably never happens since glDrawArraysInstanced is a more recent addition than texture buffers.
 *      A VBO is used to store a single instance of the vertices.
 *      A uniform array is used to store batches of position data.
 * 5. !manuallyIndexVertices && useTextureBuffer
 *      Best case scenario.
 *      Uses glDrawArraysInstanced to draw multiple instances of a single copy of the vertices in a VBO.
 *      The texture buffer is used to store position data.
 *      
 * @author Zebadiah Long
 */
public class GLRenderer extends GLCanvas implements GLEventListener, Renderer
{
	// This is actually max instances per type of geometry, but oh well, it's arbitrary right now anyway
	private static final int MAX_INSTANCES = 100000;
	private static final int FLOAT_BYTES   = Float.SIZE / Byte.SIZE;

	// Only used for uniform array position batching right now, not used for texture buffer. 
	// This could eventually be a problem.
	private static final int BATCH_SIZE    = 512;
	
	// If fixedPipelineOnly is used then manuallyIndexVertices is always false and useTextureBuffer is ignored
	private boolean fixedPipelineOnly;
	
	// Include an element index as the z-component with each vertex
	// Used for pseudo-instancing shaders
	private static boolean manuallyIndexVertices; 
	
	// Batch position data into the texture buffer, this is the preferred option and allows for much larger batches than the alternate uniform array method
	// Both methods require shader support.
	private static boolean useTextureBuffer;
	
	private IntBuffer idBuffer = IntBuffer.allocate(1);
	// only used when uniform array position batching is used (no texture buffer available)
	private FloatBuffer positionBatchBuffer;
	

	// Buffers and shader attributes
	private int currentlyBoundVertexBuffer = 0;
	private int positionBufferID = 0;
	private int defaultShaderProgram=-1, instancingShaderProgram;
	private int projectionAttribute, vertexAttribute, positionAttribute, positionOffsetAttribute, mvpAttribute;

	// All of the things that will be drawn
	// Drawables are for the more complex geometry or geometry that needs to be able to change
	private ArrayList<Drawable> drawables          = new ArrayList<Drawable>();
	// Instance geometries holds the list of simple geometries to draw instances of
	private ArrayList<Geometry> instanceGeometries = new ArrayList<Geometry>();
	// Movables hold the position and velocity for instance geometries. Each instance geometry can be used to draw many movable instances.
	private ArrayList<Movable[]> geometryInstances = new ArrayList<Movable[]>();
	
	private long timeSinceLastDraw;
	private long lastDrawTime;
	public float viewWidth, viewHeight;
	private float screenWidth, screenHeight;
	private boolean didInit = false;
	private FPSAnimator animator;
	
	public GLRenderer()
	{
		// setup OpenGL Version 2
		super(new GLCapabilities(GLProfile.get(GLProfile.GL2)));

		this.addGLEventListener(this);
		this.setSize(1800, 1000);
		animator = new FPSAnimator(this, 60);
	}

	/**
	 * Called when the OpenGL context is first made available
	 * 
	 * Sets up the GL environment and shaders if available. 
	 */
	public void init(GLAutoDrawable d)
	{
		final GL2 gl = d.getGL().getGL2();
		
		gl.glClearColor(0f, 0f, 0f, 1f);

		gl.glBlendFunc(GL2.GL_SRC_ALPHA, GL2.GL_ONE_MINUS_SRC_ALPHA);
		gl.glEnable(GL2.GL_BLEND);
		gl.glEnableClientState(GL2.GL_VERTEX_ARRAY);
		
		// Check for shader support
		fixedPipelineOnly     = !gl.isFunctionAvailable("glCreateShader");
		manuallyIndexVertices = !gl.isFunctionAvailable("glDrawArraysInstanced");
		useTextureBuffer      = gl.isFunctionAvailable("glTexBuffer");
		
		if(!fixedPipelineOnly)
		{
			// First compile the default shader
			defaultShaderProgram = ShaderLoader.compileProgram(gl, "default");
			gl.glLinkProgram(defaultShaderProgram);
	        mvpAttribute = gl.glGetUniformLocation(defaultShaderProgram, "mvp");
		
	        // Compile and link appropriate instancing shader
	        if(!manuallyIndexVertices && useTextureBuffer)
	        {
	        	instancingShaderProgram = ShaderLoader.compileProgram(gl, "instancing_texture");
	        }
        	else if(!manuallyIndexVertices && !useTextureBuffer)
        	{
        		instancingShaderProgram = ShaderLoader.compileProgram(gl, "instancing_uniform");
        	}
	        else if(manuallyIndexVertices && useTextureBuffer)
	        {
	        	instancingShaderProgram = ShaderLoader.compileProgram(gl, "pseudo_instancing_texture");
	        }
        	else // if(manuallyIndexVertices && !useTextureBuffer)
        	{
        		instancingShaderProgram = ShaderLoader.compileProgram(gl, "pseudo_instancing_uniform");
        	}
	        gl.glLinkProgram(instancingShaderProgram);
	        
	        // Grab references to the shader attributes
	        projectionAttribute = gl.glGetUniformLocation(instancingShaderProgram, "projection");
	        vertexAttribute     = gl.glGetAttribLocation(instancingShaderProgram, "vertex");
	        if(useTextureBuffer)
	        {
	        	positionAttribute       = gl.glGetUniformLocation(instancingShaderProgram, "positionSampler");
	        	positionOffsetAttribute = gl.glGetUniformLocation(instancingShaderProgram, "positionOffset");
	        }
	        else
	        {
	        	positionBatchBuffer = ByteBuffer.allocateDirect(BATCH_SIZE*2*FLOAT_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer();
	        	positionAttribute   = gl.glGetUniformLocation(instancingShaderProgram, "positions");
	        }
	
	        gl.glUseProgram(instancingShaderProgram);
	        
	        if(useTextureBuffer)
	        {
	        	// If we're using the texture buffer, set it up
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

	/**
	 * Renders all drawables and all geometryInstances
	 */
	public void display(GLAutoDrawable d)
	{
		final GL2 gl = d.getGL().getGL2();
		timeSinceLastDraw = System.currentTimeMillis() - lastDrawTime;
		lastDrawTime = System.currentTimeMillis();
		
		gl.glClear(GL2.GL_COLOR_BUFFER_BIT | GL2.GL_DEPTH_BUFFER_BIT);

		/*
		 * Draw the drawables. Also handles removing drawables when removeFromGLEngine is set
		 */
		synchronized(drawableLock)
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

		/*
		 * Draw the geometryInstances
		 */
		synchronized(instanceLock)
		{
			if(fixedPipelineOnly)
			{
				_drawInstancesFixedPipeline(gl);
			}
			else 
			{
				// Bind appropriate instancing shader
				gl.glUseProgram(instancingShaderProgram);
				
				if(useTextureBuffer)
				{
					_updatePositionBufferTexture(gl);
					_drawInstancesTextureBuffer(gl);
				}
				else // using uniform array
				{
					_drawInstancesUniformArray(gl);
				}
			}
		}
	}
	
	/**
	 * Draw geometryInstances using a uniform array to store position data
	 */
	private void _drawInstancesUniformArray(GL2 gl)
	{
		for(int g = 0; g < geometryInstances.size(); g++)
		{
			Geometry geometry = instanceGeometries.get(g);
			
			if(geometry.num_instances == 0)
			{
				// Move on if there are no longer any instances of this geometry
				continue;
			}
			
			if(geometry.hasChanged)
			{
				// Makes sure current vertex set is loaded into vertex buffer
				_compileGeometry(gl, geometry);
			}
			
			// Bind the vertex buffer and point at it
			currentlyBoundVertexBuffer = geometry.vertexBufferID;
	    	gl.glBindBuffer(GL.GL_ARRAY_BUFFER, currentlyBoundVertexBuffer);
	    	if(!manuallyIndexVertices)
		    {
				gl.glVertexPointer(2, GL.GL_FLOAT, 0, 0);
		    }
			else 
			{
		    	// Pseudo instancing requires an element index stored in the z-component of each vertex, so 3 floats are required
				gl.glVertexPointer(3, GL.GL_FLOAT, 0, 0);
			}
	    	
			// Using a uniform array for position data. Batching is required.
			int b;
			for(b = 0; b < geometry.num_instances/BATCH_SIZE; b++)
			{
				// Load a batch of position into the uniform array on draw a batch of instances
				_updatePositionBufferArray(gl, geometry, BATCH_SIZE, b*BATCH_SIZE);
				_drawInstances(gl, geometry, BATCH_SIZE);
			}
			// Get the remainder
			int num_remaining = geometry.num_instances - b*BATCH_SIZE;
			_updatePositionBufferArray(gl, geometry, num_remaining, b*BATCH_SIZE);
			_drawInstances(gl, geometry, num_remaining);
		}
	}
	
	/**
	 * Draw instanceGeometries using the texture buffer to store position data
	 */
	private void _drawInstancesTextureBuffer(GL2 gl)
	{
		int offset = 0;
		for(int g = 0; g < geometryInstances.size(); g++)
		{
			Geometry geometry = instanceGeometries.get(g);
			
			if(geometry.num_instances == 0)
			{
				// Move on if there are no longer any instances of this geometry
				continue;
			}
			
			if(geometry.hasChanged)
			{
				// Makes sure current vertex set is loaded into vertex buffer
				_compileGeometry(gl, geometry);
			}
			
			// Bind the vertex buffer and point at it
			currentlyBoundVertexBuffer = geometry.vertexBufferID;
	    	gl.glBindBuffer(GL.GL_ARRAY_BUFFER, currentlyBoundVertexBuffer);
	    	if(!manuallyIndexVertices)
		    {
				gl.glVertexPointer(2, GL.GL_FLOAT, 0, 0);
		    }
			else 
			{
		    	// Pseudo instancing requires an element index stored in the z-component of each vertex, so 3 floats are required
				gl.glVertexPointer(3, GL.GL_FLOAT, 0, 0);
			}
	    	
			// Using the texture buffer, all the positions are already loaded and bound for all geometry instances
			// set the positionOffset in the shader and draw some instances
	    	gl.glUniform1i(positionOffsetAttribute, offset*2);
	    	_drawInstances(gl, geometry, geometry.num_instances);
	    	
	    	offset += geometry.num_instances;
		}
	}
	
	/**
	 * Used the old fixed pipeline to draw geometryInstances
	 */
	private void _drawInstancesFixedPipeline(GL2 gl)
	{
		for(int g = 0; g < geometryInstances.size(); g++)
		{
			Geometry geometry = instanceGeometries.get(g);
			
			if(geometry.num_instances == 0)
			{
				// Move on if there are no longer any instances of this geometry
				continue;
			}
			
			if(geometry.hasChanged)
			{
				// Makes sure current vertex set is loaded into vertex buffer
				_compileGeometry(gl, geometry);
			}
			
			// Bind the vertex buffer and point at it
			currentlyBoundVertexBuffer = geometry.vertexBufferID;
	    	gl.glBindBuffer(GL.GL_ARRAY_BUFFER, currentlyBoundVertexBuffer);
			gl.glVertexPointer(2, GL.GL_FLOAT, 0, 0);
			
			// Fixed pipeline code. Use the standard matrix stack, no shaders, no instancing.
			// Draw things one at a time
			Movable[] instances = geometryInstances.get(g);
			for(int i = 0; i < geometry.num_instances; i++)
			{
				gl.glPushMatrix();
				gl.glTranslatef(instances[i].x, instances[i].y, 0);
				gl.glDrawArrays(GL2.GL_TRIANGLES, 0, geometry.vertices.length/2);
				gl.glPopMatrix();
			}
		}
	}
	
	/**
	 * Draw many instances of a type of geometry.
	 */
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
	
	/**
	 * Upload a batch of instance positions for a single geometry type into a uniform array
	 */
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
	
	/**
	 * Update the instance positions in the texture buffer
	 */
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
	
	/**
	 * Assemble and bind some vertex data
	 */
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
	
	/**
	 * Load one instance of some geometry's vertices into a buffer.
	 */
	private void _finalizeGeometry(GL2 gl, Geometry g)
	{
		g.hasChanged = false;
		
		if(g.vertices == null) return;
		
		int numBytes = g.vertices.length * FLOAT_BYTES;
        
        g.vertexBufferID = _generateBufferID(gl);
        
        currentlyBoundVertexBuffer = g.vertexBufferID;
		gl.glBindBuffer(GL2.GL_ARRAY_BUFFER, currentlyBoundVertexBuffer);
		
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
	
	/**
	 * Load numInstances of some geometry's vertices into a buffer.
	 * Each vertices z-component functions as an instance index
	 */
	private void _finalizeGeometry(GL2 gl, Geometry g, int numInstances)
	{
		g.hasChanged = false;
		
		if(g.vertices == null) return;
		

		int numBytes = 3*g.vertices.length*FLOAT_BYTES*MAX_INSTANCES/2;
        
		g.vertexBufferID = _generateBufferID(gl);
		
		currentlyBoundVertexBuffer = g.vertexBufferID;
		gl.glBindBuffer(GL2.GL_ARRAY_BUFFER, currentlyBoundVertexBuffer);
		
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

	/**
	 * Draw a single Drawable thing
	 */
	private void _drawDrawable(Drawable thing, GL2 gl)
	{
		// Make sure Drawable is initialized
		if (!thing.didInit)
		{
			thing.init(viewWidth, viewHeight);
			
			if(thing.geometry != null && thing.geometry.hasChanged && thing.geometry.vertices != null)
			{
				_compileGeometry(gl, thing.geometry);
			}
		}
		
		// Transform matrices
		if(fixedPipelineOnly)
		{
			// Crappy fixed pipeline transformations
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

			// Rotation and scaling not implemented for shaders yet
	
			gl.glUniformMatrix3fv(mvpAttribute, 1, false, Matrix3x3.getMatrix());
		}
		
		// Render the drawable
		if (thing.vertices != null)
		{
			_render(gl, thing.drawMode, thing);
		}
		// Render optional geometry if the drawable has one
		if (thing.geometry != null && thing.geometry.vertices != null)
		{
			_render(gl, thing.geometry.drawMode, thing.geometry);
		}
		
		// Recursively draw child drawables
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
	
	/**
	 * Render a single piece of geometry
	 */
	public void _render(GL2 gl, int draw_mode, Geometry geometry)
	{
		if(geometry.vertexBufferID != currentlyBoundVertexBuffer)
		{
			if(geometry.vertexBufferID == 0) return;
			
			gl.glBindBuffer(GL2.GL_ARRAY_BUFFER, geometry.vertexBufferID);
			currentlyBoundVertexBuffer = geometry.vertexBufferID;

			gl.glVertexPointer(3, GL.GL_FLOAT, 0, 0);
		}
		gl.glDrawArrays(draw_mode, 0, geometry.getNumPoints());
	}

	/**
	 * Render a single Drawable
	 */
	public void _render(GL2 gl, int draw_mode, Drawable thing)
	{
		gl.glColor3f(1, 1, 1);
		if(thing.vertexBuffer == null) return;
		if(currentlyBoundVertexBuffer != 0)
		{
			gl.glBindBuffer(GL2.GL_ARRAY_BUFFER, 0);
		}
		gl.glVertexPointer(3, GL.GL_FLOAT, 0, thing.vertexBuffer);
		gl.glDrawArrays(draw_mode, 0, thing.getNumPoints());
		currentlyBoundVertexBuffer = 0;
	}
	
	/**
	 * Recursively reshape all drawables
	 */
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

	/**
	 * Called by OpenGL whenever the view changes size
	 */
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
			// Crappy fixed rendering pipeline
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
	
	/**
	 * Add an instance of some geometry to the instanceGeometries list so it will be drawn
	 */
	@Override
	public void addGeometryInstance(float x, float y, Geometry geom) 
	{
		synchronized(instanceLock)
		{
			if(geom.geometryID == -1)
			{
				instanceGeometries.add(geom);
				geometryInstances.add(new Movable[MAX_INSTANCES]);
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

	/**
	 * Removes an instance of some geometry from the instanceGeometries list
	 */
	@Override
	public boolean removeInstanceGeometry(int g, int i) 
	{
		synchronized(instanceLock)
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
	
	/**
	 * Get the geometry id in the instanceGeometries list
	 */
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
		synchronized(instanceLock)
		{
			return geometryInstances;
		}
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
		synchronized(drawableLock)
		{
			drawables.add(d);
		}
	}

	public void screenToViewCoords(float[] xy)
	{
		float viewX = (xy[0] / screenWidth) * viewWidth;
		float viewY = viewHeight - (xy[1] / screenHeight) * viewHeight;
		xy[0] = viewX;
		xy[1] = viewY;
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

	@Override
	public void dispose(GLAutoDrawable drawable) {}
}