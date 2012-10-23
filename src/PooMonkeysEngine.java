import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.io.IOException;
import java.util.ArrayList;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import poomonkeys.common.AimingHUD;
import poomonkeys.common.DirtGeometry;
import poomonkeys.common.GLClickEvent;
import poomonkeys.common.GLClickListener;
import poomonkeys.common.GameEngine;
import poomonkeys.common.Geometry;
import poomonkeys.common.Movable;
import poomonkeys.common.PhysicsController;
import poomonkeys.common.Player;
import poomonkeys.common.Point2D;
import poomonkeys.common.Shot;
import poomonkeys.common.SocketListener;
import poomonkeys.common.SocketUtil;
import poomonkeys.common.Terrain;
import poomonkeys.common.TerrainGenerator;

public class PooMonkeysEngine implements WindowListener, MouseListener, MouseMotionListener, ActionListener, SocketListener, GLClickListener, GameEngine
{
	
	public ArrayList<Player> players = new ArrayList<Player>();
	int gameState = STATE_CHOOSE_ANGLE;
	public int currentPlayer = 0;
	AimingHUD angleHUD;
	ArrayList<Shot> shots = new ArrayList<Shot>();
	public Point2D gravity = new Point2D(0, -.0003f);
	
	JMenuBar menuBar = new JMenuBar();
	JMenu menu;
	JMenuItem hostAGame, connectToAGame;

	GLRenderer renderer = null;
	PhysicsController physicsController = null;
	
	JFrame the_frame;
	
	private Terrain the_terrain;

	public ArrayList<Geometry> instanceGeometries = new ArrayList<Geometry>();
	private ArrayList<Movable[]> movables = new ArrayList<Movable[]>();
	
	static PooMonkeysEngine engine = null;
	
	public static void main(String[] args) 
    {
		PooMonkeysEngine.getInstance();
    }
	
	public static PooMonkeysEngine getInstance()
	{
		if(engine == null) engine = new PooMonkeysEngine();
		return engine;
	}

	public PooMonkeysEngine()
	{
		renderer = GLRenderer.getInstance();
		renderer.addMouseListener(this);
		renderer.addMouseMotionListener(this);
		
	    the_frame = new JFrame("Hello World");
	    
	    menu = new JMenu("Game");
	    menuBar.add(menu);
	    hostAGame = new JMenuItem("Host a game");
	    hostAGame.setName("host");
	    hostAGame.addActionListener(this);
	    menu.add(hostAGame);
	    connectToAGame = new JMenuItem("Connect to a Game");
	    connectToAGame.setName("connect");
	    connectToAGame.addActionListener(this);
	    menu.add(connectToAGame);
	    the_frame.setJMenuBar(menuBar);
	    
	    the_frame.getContentPane().add(renderer);
	
	    // shutdown the program on windows close event
	    the_frame.addWindowListener(this);
	
	    the_frame.setSize(the_frame.getContentPane().getPreferredSize());
	    the_frame.setVisible(true);
	    
	    players.add(new Player());
		renderer.registerDrawable(players.get(0).tank);
	    
	    angleHUD = AimingHUD.getInstance();
	    angleHUD.startButton.addGLClickListener(this);
	    //renderer.registerDrawable(angleHUD);
	    
	    gameState = STATE_CHOOSE_ANGLE;
	    gameState = STATE_TESTING;
	    
	    renderer.start();
	}
	
	public void init()
	{		
		the_terrain = new Terrain(this);
	    the_terrain.setWidth(renderer.viewWidth);
	    the_terrain.setHeight(renderer.viewHeight);
	    TerrainGenerator.generate(the_terrain);
	    
	    renderer.registerDrawable(the_terrain);
	    
		the_terrain.addTankRandom(players.get(0).tank);
		
		physicsController = new PhysicsController(this);
	}
	
	public void delete()
	{
		angleHUD.delete();
		PooMonkeysEngine.engine = null;
	}
	
	public void setCurrentTankAngle(float rotation)
	{
		players.get(currentPlayer).setAngle(rotation);
	}

	public void fireShot() 
	{
		GLRenderer renderer = GLRenderer.getInstance();
		gameState = STATE_FIRING_SHOT;
		angleHUD.removeFromGLEngine = true;
		Shot shot = new Shot(players.get(currentPlayer), 0, angleHUD.getPower(), renderer.viewWidth, renderer.viewHeight);
		players.get(currentPlayer).fireShot(shot);
		renderer.registerDrawable(shot);
		physicsController.addCollidable(shot);
		shots.add(shot);
	}

	public void enemyFiredShot(int enemyID, float x, float y, float vx, float vy) 
	{
		GLRenderer renderer = GLRenderer.getInstance();
		gameState = STATE_ENEMY_FIRING_SHOT;
		Shot shot = new Shot(players.get(enemyID), 0, x, y, vx, vy, renderer.viewWidth, renderer.viewHeight);
		players.get(enemyID).fireShot(shot);
		renderer.registerDrawable(shot);
		physicsController.addCollidable(shot);
		shots.add(shot);
	}
	
	@Override
	public void windowClosing(WindowEvent arg0) 
	{
		System.exit(0);
	}
	
	@Override
	public void mouseDragged(MouseEvent e) 
	{
		float x = e.getX();
        float y = e.getY();
        float real_xy[] = {x, y};
        GLRenderer renderer = GLRenderer.getInstance();
		renderer.screenToViewCoords(real_xy);
		if(gameState == STATE_CHOOSE_ANGLE) {
			angleHUD.touch(real_xy[0], real_xy[1], renderer.viewWidth, renderer.viewHeight);
			setCurrentTankAngle(angleHUD.anglePicker.line.getRotation());
		}
	}
	

	@Override
	public void mouseReleased(MouseEvent e) 
	{
		float x = e.getX();
        float y = e.getY();
		float real_xy[] = {x, y};
		GLRenderer renderer = GLRenderer.getInstance();
		renderer.screenToViewCoords(real_xy);
		switch(gameState)
		{
			case STATE_CHOOSE_ANGLE:
				angleHUD.click(real_xy[0], real_xy[1], renderer.viewWidth, renderer.viewHeight);
				break;
			case STATE_TESTING:
				the_terrain.explodeCircle(real_xy[0]-the_terrain.p[0], real_xy[1]-the_terrain.p[1], 5f);
				float[] f = new float[3];
				f[0] = real_xy[0]; f[1] = real_xy[1]; f[2] = 10;
				physicsController.pointForces.add(f);
				if(!physicsController.hasCollidable(players.get(0).tank))
				{
					physicsController.addCollidable(players.get(0).tank);
				}
				//the_terrain.dropDirt(real_xy[0], real_xy[1]);
				break;
		}
	}

	@Override
	public void actionPerformed(ActionEvent e) 
	{
		String action = ((JMenuItem)e.getSource()).getName();
		
		if(action.equals("host")) 
		{
			try 
			{
				SocketUtil.hostGame();
			} catch (IOException e1) {}
		}
		else if(action.equals("connect"))
		{
			String ip = (String)JOptionPane.showInputDialog(
					the_frame,
                    "Enter the IP to connect to",
                    "Direct Connect",
                    JOptionPane.QUESTION_MESSAGE);

			if ((ip != null) && (ip.length() > 0)) 
			{
				SocketUtil.connectToIP(ip);
			}
		}
	}
	
	@Override
	public void playerJoined() 
	{
		players.add(new Player());
	}

	@Override
	public void incomingMessage(int socket_id, String[] data) 
	{
		// Player id (first argument) is always one more than socket id, this 
		// is because the first player's id is 0 and the first player has no 
		// socket. Then as each other player connects they are given a socket_id
		// and then immediately added to the player list, so the relationship
		// should always hold. The same is true for removing players (I think).
		engine.enemyFiredShot(socket_id+1, 
								Float.parseFloat(data[0]), 
								Float.parseFloat(data[1]), 
								Float.parseFloat(data[2]), 
								Float.parseFloat(data[3]));
	}

	@Override
	public void glClicked(GLClickEvent evt) 
	{
		if(evt.getSource() == angleHUD.startButton)
		{
			fireShot();
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
				movables.add(new Movable[MAX_MOVABLES]);
				geom.geometryID = instanceGeometries.size()-1;
			}
			
			Movable movable = new Movable();
			movable.x = x;
			movable.y = y;
			movable.geometryID = geom.geometryID; // geometry id
			
			movables.get(geom.geometryID)[geom.num_instances] = movable;
			geom.num_instances++;
		}
	}

	@Override
	public boolean removeMovable(int g, int i) 
	{
		synchronized(movableLock)
		{
			Movable[] instances = movables.get(g);
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
			return movables;
		}
	}

	@Override
	public Terrain getTerrain() 
	{
		return the_terrain;
	}

	@Override
	public void mouseClicked(MouseEvent e){}
	@Override
	public void mouseMoved(MouseEvent arg0){}
	@Override
	public void mouseEntered(MouseEvent arg0) {}
	@Override
	public void mouseExited(MouseEvent arg0) {}
	@Override
	public void mousePressed(MouseEvent arg0) {}
	@Override
	public void windowActivated(WindowEvent arg0) {}
	@Override
	public void windowClosed(WindowEvent arg0) {}
	@Override
	public void windowDeactivated(WindowEvent arg0) {}
	@Override
	public void windowDeiconified(WindowEvent arg0) {}
	@Override
	public void windowIconified(WindowEvent arg0) {}
	@Override
	public void windowOpened(WindowEvent arg0) {}

}
