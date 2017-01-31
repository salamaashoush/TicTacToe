
package tictactoe.network;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.codehaus.jackson.map.ObjectMapper;
import tictactoe.helpers.DBManager;
import tictactoe.models.Player;
import tictactoe.network.messages.EndGameMessage;
import tictactoe.network.messages.GameChatTextMessage;
import tictactoe.network.messages.GameMoveMessage;
import tictactoe.network.messages.MessageTypes;


public class Game implements Runnable, Session.GameMessagesListener{

   

   
    
    private class GameMove{
        
        public Session player;
        public GameMoveMessage mvMsg;
    }
    
    private class GameTextMsg{
        public Session player;
        public GameChatTextMessage textMsg;
    }
    
    private int moveCount;
    private int gridSize;
    private enum cellState{Blank, X, O};
    private enum winState{X, O, Draw,NoWin};
    private  cellState [][] gameBoard;
    private Session p1;
    private Session p2;
    
    private ObjectMapper objectMapper;
    
    private DBManager dbManager;
    
    private Map<Session, String> playersSymbols = new HashMap<>();
    
    private Thread th;
    
    private boolean isStarted = false;
    
    private BlockingQueue<GameMove> queue = new LinkedBlockingQueue<GameMove>();
//    private BlockingQueue<GameTextMsg> chatQueue = new LinkedBlockingQueue<>();
    
   public Game(int gridSize, Session p1, Session p2){
        
         this.moveCount = 0;
         this.gridSize = gridSize;
         
         this.p1 = p1;
         this.p2 = p2;
         
         this.p1.setGameMessageListener(this);
         this.p2.setGameMessageListener(this);
         
         
         this.objectMapper = new ObjectMapper();
         this.dbManager = DBManager.getInstance();
         
         
        Player player1 = this.p1.getPlayer();
        Player player2 = this.p2.getPlayer();
        player1.setStatus(Player.STATUS_PLAYING);
        player2.setStatus(Player.STATUS_PLAYING);
        this.dbManager.update(player1);
        this.dbManager.update(player2);
         
         this.playersSymbols.put(p1, cellState.X.toString());
         this.playersSymbols.put(p2, cellState.O.toString());
        
        gameBoard = new cellState [gridSize][gridSize];
         
        for(int row =0 ; row <gridSize ; row++){
            for(int col = 0 ; col<gridSize ; col++){
                this.gameBoard[row][col] = cellState.Blank;
            }
        }
    }
   
   public void start(){
       
       if(!isStarted){
           
           isStarted = true;
           
           this.th = new Thread(this);
           this.th.start();
           
       }
       
   }
   
   public void stop(){
       
       isStarted = false;
       
       this.th.interrupt();
       
       if(this.p1 != null){
           this.p1.setGameMessageListener(null);
       }
       
       if(this.p2 != null){
           this.p2.setGameMessageListener(null);
       }
   }
   
   public void run(){
       
       while(this.isStarted){
           
           try {
               GameMove gameMv = this.queue.take();
               
               
               String symblStr = this.playersSymbols.get(gameMv.player);
               cellState symbl = cellState.valueOf(symblStr);
               
               if(updateCell(symbl, gameMv.mvMsg.getX(), gameMv.mvMsg.getY())){
                   
                   Session opponent = getOpponent(gameMv.player);
                   
                   try {
                       opponent.send(MessageTypes.MSG_TYPE_GAME_MOVE, this.objectMapper.writeValueAsString(gameMv.mvMsg));
                   } catch (IOException ex) {
                       Logger.getLogger(Game.class.getName()).log(Level.SEVERE, null, ex);
                   }
                   
                   winState wState = checkWin(symbl, gameMv.mvMsg.getX(), gameMv.mvMsg.getY());
                   
                   if(wState == winState.O || wState == winState.X){
                       
                       EndGameMessage endMessage = new EndGameMessage();
                       endMessage.setStatus("Winner");
                       
                       try {
                           gameMv.player.send(MessageTypes.MSG_TYPE_GAME_END, this.objectMapper.writeValueAsString(endMessage));
                       } catch (IOException ex) {
                           Logger.getLogger(Game.class.getName()).log(Level.SEVERE, null, ex);
                       }
                       
                       Player winner = gameMv.player.getPlayer();
                       winner.addPoints(5);
                       this.dbManager.update(winner);
                       
                       endMessage.setStatus("Looser");
                       
                       try {
                           opponent.send(MessageTypes.MSG_TYPE_GAME_END, this.objectMapper.writeValueAsString(endMessage));
                       } catch (IOException ex) {
                           Logger.getLogger(Game.class.getName()).log(Level.SEVERE, null, ex);
                       }
                       
                       stop();
                       
                   }else if(wState == winState.Draw){
                       
                        EndGameMessage endMessage = new EndGameMessage();
                        endMessage.setStatus("Winner");
                       
                       try {
                           this.p1.send(MessageTypes.MSG_TYPE_GAME_END, this.objectMapper.writeValueAsString(endMessage));
                           this.p2.send(MessageTypes.MSG_TYPE_GAME_END, this.objectMapper.writeValueAsString(endMessage));
                       } catch (IOException ex) {
                           Logger.getLogger(Game.class.getName()).log(Level.SEVERE, null, ex);
                       }
                       
                       
                       stop();
                   }
                   
               }
               
           } catch (InterruptedException ex) {
               Logger.getLogger(Game.class.getName()).log(Level.SEVERE, null, ex);
           }
           
           
           
       }
       
   }
   
   
    @Override
    public void onGameMoveMessage(Session s, GameMoveMessage mvMsg) {
        
        GameMove gameMv = new GameMove();
        gameMv.player = s;
        gameMv.mvMsg = mvMsg;
        
        this.queue.add(gameMv);
    }
    
     @Override
    public void onGameChatTextMessage(Session s, GameChatTextMessage textMsg) {
        try {
            GameTextMsg msg = new GameTextMsg();
            msg.player = s;
            msg.textMsg = textMsg;
            
            Session opponent = this.getOpponent(s);
            opponent.send(MessageTypes.MSG_TYPE_CHAT_TEXT, this.objectMapper.writeValueAsString(textMsg));
        } catch (IOException ex) {
            Logger.getLogger(Game.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
     @Override
    public void onGameEnd(Session s) {
       Session opponent = getOpponent(s);
       
       if(opponent.isStarted()){
           Player player = opponent.getPlayer();
           player.setStatus(Player.STATUS_IDLE);
           this.dbManager.update(player);
       }
       stop();
    }
    
    private boolean validateMove(int x , int y){
        return this.gameBoard[x][y] == cellState.Blank && x < gridSize && x >= 0 && y< gridSize && y >= 0;
    }

    public boolean updateCell(cellState state , int x ,int y){
        
        if( validateMove(x, y)){
            this.gameBoard[x][y] = state;
            this.moveCount ++;
            return true;
        }else{
            return false;
        }
    }
    
//    private void  updatePointsOfWinner(){
//        Session winner = this.ga
//    }
	
    
    public winState checkWin(cellState state , int x , int y){
        
        
        for(int i =0 ; i <this.gridSize ; i++){
            
            if(this.gameBoard[x][i] != state)
                break;
            if(i == gridSize-1)
                return winState.valueOf(state.toString());
        }
        
        for(int i =0 ; i<this.gridSize ; i++){
            
            if(this.gameBoard[i][y] != state)
                break;
            if(i == gridSize-1)
                return winState.valueOf(state.toString());
        }
        
         if(x == y){
    
            for(int i = 0; i < this.gridSize; i++){
                if(this.gameBoard[i][i] != state)
                    break;
                if(i == gridSize-1){
                    return winState.valueOf(state.toString());
                }
            }
        }
         
         if(x + y == this.gridSize-1){
             
            for(int i = 0;i<this.gridSize;i++){
                
                if(this.gameBoard[i][(this.gridSize-1)-i] != state)
                    break;
                if(i == this.gridSize-1){
                    return winState.valueOf(state.toString());
                }
            }
        }
         
        if(this.moveCount == Math.pow(this.gridSize, 2) -1)
             return winState.Draw;
        
        return winState.NoWin;
    }
    
    private Session getOpponent(Session player){
        
        Session opponent = null;
        
        if(player != this.p1){
            opponent = this.p1;
        }else if(player != this.p2){
            opponent = this.p2;
        }
        
        return opponent;
    }
}
