import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.ListIterator;

import javax.media.opengl.GL;
import javax.media.opengl.GL2;
import javax.media.opengl.GLAutoDrawable;
import javax.media.opengl.GLCapabilities;
import javax.media.opengl.GLEventListener;
import javax.media.opengl.GLProfile;
import javax.media.opengl.awt.GLCanvas;
import javax.media.opengl.glu.GLU;

import poomonkeys.common.DirtGeometry;
import poomonkeys.common.Drawable;
import poomonkeys.common.GameEngine;
import poomonkeys.common.Geometry;
import poomonkeys.common.Renderer;

import com.jogamp.opengl.util.FPSAnimator;

public class GLRenderer extends GLCanvas implements GLEventListener, Renderer
{

	private static final long serialVersionUID = -8513201172428486833L;
	public float viewWidth, viewHeight;
	public float screenWidth, screenHeight;

	private static GLRenderer instance = null;
	
	PooMonkeysEngine engine;
	public long timeSinceLastDraw;
	private long lastDrawTime;
	
	private int currentlyBoundBuffer = 0;

	private FPSAnimator animator;

	private ArrayList<Drawable> drawables = new ArrayList<Drawable>();
	
	private boolean didInit = false;

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
	}

	public void display(GLAutoDrawable d)
	{
		final GL2 gl = d.getGL().getGL2();
		timeSinceLastDraw = System.currentTimeMillis() - lastDrawTime;
		lastDrawTime = System.currentTimeMillis();
		gl.glClear(GL2.GL_COLOR_BUFFER_BIT | GL2.GL_DEPTH_BUFFER_BIT);
		//gl.glActiveTexture(GL2.GL_TEXTURE0);

		gl.glLoadIdentity();

		synchronized(drawables)
		{
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
			

			int num_movables = engine.getNumMovables();
			if(num_movables > 0) 
			{
				synchronized(GameEngine.movableLock)
				{
					float[] movables = engine.getMovables();
					int geometryID = (int) movables[GameEngine.GEOM];
					Geometry geometry = engine.getGeometry(geometryID);
					if(geometry.hasChanged)
					{
						geometry.buildGeometry(viewWidth, viewHeight);
						geometry.finalizeGeometry();
						if(geometry.needsCompile && geometry.vertices != null)
						{
							geometry.needsCompile = false;
						
							int bytesPerFloat = Float.SIZE / Byte.SIZE;
							
						    int numBytes = geometry.vertices.length * bytesPerFloat;
						    
							IntBuffer vertexBufferID = IntBuffer.allocate(1);
							gl.glGenBuffers(1, vertexBufferID);
							geometry.vertexBufferID = vertexBufferID.get(0);
							
							gl.glBindBuffer(GL2.GL_ARRAY_BUFFER, geometry.vertexBufferID);
							gl.glBufferData(GL2.GL_ARRAY_BUFFER, numBytes, geometry.vertexBuffer, GL2.GL_STATIC_DRAW);
							gl.glVertexPointer(3, GL.GL_FLOAT, 0, 0);
							currentlyBoundBuffer = geometry.vertexBufferID;
						}
					}
					for(int i = 0; i < num_movables; i++)
					{
						int dirt_index = i*GameEngine.ITEMS_PER_MOVABLE;
						if((int) movables[dirt_index+GameEngine.GEOM] != geometryID)
						{
							geometryID = (int) movables[dirt_index+GameEngine.GEOM];
							geometry = engine.getGeometry(geometryID);
							
							if(geometry.hasChanged)
							{
								geometry.buildGeometry(viewWidth, viewHeight);
								geometry.finalizeGeometry();
								if(geometry.needsCompile && geometry.vertices != null)
								{
									geometry.needsCompile = false;
								
									int bytesPerFloat = Float.SIZE / Byte.SIZE;
									
								    int numBytes = geometry.vertices.length * bytesPerFloat;
								    
									IntBuffer vertexBufferID = IntBuffer.allocate(1);
									gl.glGenBuffers(1, vertexBufferID);
									geometry.vertexBufferID = vertexBufferID.get(0);
									
									gl.glBindBuffer(GL2.GL_ARRAY_BUFFER, geometry.vertexBufferID);
									gl.glBufferData(GL2.GL_ARRAY_BUFFER, numBytes, geometry.vertexBuffer, GL2.GL_STATIC_DRAW);
									gl.glVertexPointer(3, GL.GL_FLOAT, 0, 0);
									currentlyBoundBuffer = geometry.vertexBufferID;
								}
							}
						}
						
						gl.glPushMatrix();
						gl.glTranslatef(movables[dirt_index+GameEngine.X], movables[dirt_index+GameEngine.Y], 0);
						_render(gl, geometry.drawMode, geometry);
						gl.glPopMatrix();
					}
				}
			}
		}
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
			//gl.glColor3f(0, 1, 0);
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
}