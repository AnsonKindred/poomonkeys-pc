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
import poomonkeys.common.GameEngine;
import poomonkeys.common.Geometry;
import poomonkeys.common.Matrix3x3;
import poomonkeys.common.Movable;
import poomonkeys.common.Renderer;
import poomonkeys.common.ShaderLoader;

import com.jogamp.opengl.util.FPSAnimator;

public class GLRenderer extends GLCanvas implements GLEventListener, Renderer
{

	private static final long serialVersionUID = -8513201172428486833L;
	
	private static final int MAX_INSTANCES = 1000000;
	private static final int FLOAT_BYTES  = Float.SIZE / Byte.SIZE;
	
	private static final int INSTANCING_DEFAULT = 0; // real instancing
	private static final int INSTANCING_TEXTURE = 1; // use the texture buffer to do pseudo instancing
	private static final int INSTANCING_UNIFORM = 2; // use uniforms to do pseudo instancing. Requires drawing many batches.
	private static int instancingMode = INSTANCING_TEXTURE;
	
	public float viewWidth, viewHeight;
	public float screenWidth, screenHeight;

	private static GLRenderer instance = null;
	IntBuffer idBuffer = IntBuffer.allocate(1);
	
	PooMonkeysEngine engine;
	public long timeSinceLastDraw;
	private long lastDrawTime;
	
	private int currentlyBoundBuffer = 0;
	private int positionBufferID = 0;
	
	private FPSAnimator animator;

	private ArrayList<Drawable> drawables = new ArrayList<Drawable>();
	
	private boolean didInit = false;
	
	// Shader attributes
	int shaderProgram;
	int projectionAttribute, vertexAttribute, positionAttribute, positionOffsetAttribute;

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

	public void init(GLAutoDrawable d)
	{
		engine = PooMonkeysEngine.getInstance();
		
		final GL2 gl = d.getGL().getGL2();
		gl.glClearColor(0f, 0f, 0f, 1f);

		gl.glBlendFunc(GL2.GL_SRC_ALPHA, GL2.GL_ONE_MINUS_SRC_ALPHA);
		gl.glEnable(GL2.GL_BLEND);
		gl.glEnableClientState(GL2.GL_VERTEX_ARRAY);
		
		boolean VBOsupported = gl.isFunctionAvailable("glGenBuffersARB") &&
                gl.isFunctionAvailable("glBindBufferARB") &&
                gl.isFunctionAvailable("glBufferDataARB") &&
                gl.isFunctionAvailable("glDeleteBuffersARB");
		
		System.out.println("VBO Supported: " + VBOsupported);
		
		shaderProgram = ShaderLoader.compileProgram(gl, "default");
        gl.glLinkProgram(shaderProgram);
        
        // Grab references to the shader attributes
        projectionAttribute     = gl.glGetUniformLocation(shaderProgram, "projection");
        vertexAttribute         = gl.glGetAttribLocation(shaderProgram, "vertex");
        positionAttribute       = gl.glGetUniformLocation(shaderProgram, "positionSampler");
        positionOffsetAttribute = gl.glGetUniformLocation(shaderProgram, "positionSamplerOffset");
        
	    _preparePositionBuffer(gl);
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
	    gl.glUniform1f(positionAttribute, 0); // 0 means TEXTURE0
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

		gl.glLoadIdentity();

		synchronized(drawables)
		{
			ListIterator<Drawable> itr = drawables.listIterator();
			gl.glUseProgram(0);
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

		synchronized(GameEngine.movableLock)
		{
			gl.glUseProgram(shaderProgram);
			
			ArrayList<Movable[]> movables = engine.getMovables();

			gl.glBindBuffer(GL2.GL_TEXTURE_BUFFER, positionBufferID);
			ByteBuffer textureBuffer = gl.glMapBuffer(GL2.GL_TEXTURE_BUFFER, GL2.GL_WRITE_ONLY);
			FloatBuffer textureFloatBuffer = textureBuffer.order(ByteOrder.nativeOrder()).asFloatBuffer();
			
			for(int g = 0; g < movables.size(); g++)
			{
				Movable[] instances = movables.get(g);
				Geometry geometry = engine.getGeometry(g);
				
				if(geometry.hasChanged)
				{
					_compileGeometry(gl, geometry);
				}
				
				for(int i = 0; i < geometry.num_instances; i++)
				{
					textureFloatBuffer.put(instances[i].x);
					textureFloatBuffer.put(instances[i].y);
				}
			}
			
			gl.glUnmapBuffer(GL2.GL_TEXTURE_BUFFER);
			
			int offset = 0;
			for(int g = 0; g < movables.size(); g++)
			{
				Geometry geometry = engine.getGeometry(g);
				
				if(geometry.num_instances == 0)
				{
					continue;
				}
				
		    	currentlyBoundBuffer = geometry.vertexBufferID;
		    	gl.glBindBuffer(GL.GL_ARRAY_BUFFER, currentlyBoundBuffer);
				gl.glVertexPointer(2, GL.GL_FLOAT, 0, 0);

		    	gl.glUniform1i(positionOffsetAttribute, offset*2);
		    	
		    	gl.glDrawArraysInstanced(GL2.GL_TRIANGLES, 0, geometry.vertices.length/2, geometry.num_instances);
		    	offset += geometry.num_instances;
			}
		}
	}
	
	private void _compileGeometry(GL2 gl, Geometry geometry)
	{
		geometry.buildGeometry(viewWidth, viewHeight);
		geometry.finalizeGeometry();
		
	    int numBytes = geometry.vertices.length * FLOAT_BYTES;
	    
		IntBuffer vertexBufferID = IntBuffer.allocate(1);
		gl.glGenBuffers(1, vertexBufferID);
		geometry.vertexBufferID = vertexBufferID.get(0);
		
		gl.glBindBuffer(GL2.GL_ARRAY_BUFFER, geometry.vertexBufferID);
		gl.glBufferData(GL2.GL_ARRAY_BUFFER, numBytes, geometry.vertexBuffer, GL2.GL_STATIC_DRAW);
		gl.glVertexPointer(2, GL.GL_FLOAT, 0, 0);
		
		currentlyBoundBuffer = geometry.vertexBufferID;
		geometry.hasChanged = false;
	}

	private void _drawDrawable(Drawable thing, GL2 gl)
	{
		
		if (!thing.didInit)
		{
			thing.init(viewWidth, viewHeight);
			
			if(thing.geometry != null && thing.geometry.needsCompile && thing.geometry.vertices != null)
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
			}
		}
		
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

		gl.glPopMatrix();
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
		
		gl.glMatrixMode(GL2.GL_PROJECTION);
		gl.glLoadIdentity();
		(new GLU()).gluOrtho2D(0, viewWidth, 0, viewHeight);
		
		Matrix3x3.ortho(0, viewWidth, 0, viewHeight);
		

		gl.glUseProgram(shaderProgram);
		// Send the projection matrix to the shader, only needs to be sent once per resize
        gl.glUniformMatrix3fv(projectionAttribute, 1, false, Matrix3x3.getMatrix());
		gl.glUseProgram(0);

		gl.glMatrixMode(GL2.GL_MODELVIEW);
		gl.glLoadIdentity();
        
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