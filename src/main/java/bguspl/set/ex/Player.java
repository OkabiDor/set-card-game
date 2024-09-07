package bguspl.set.ex;

import bguspl.set.Env;

import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.Random;


/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    //added fields
    /** 
     * The dealer of the game. needs to be notified when a player placed 3 tokens.,
    */
    private Dealer dealer;

    /**
     * Queue of tokens, size>0 if player can place tokens. if player removes tokens, queue.add(token)
     * the values inside the items of the queue are not relevant
     */
    public ArrayBlockingQueue<Integer> keyPressQueue = new ArrayBlockingQueue<>(3);

    public ArrayBlockingQueue<Integer> myTokensQueue = new ArrayBlockingQueue<>(3);


    private long delay = -1;

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.dealer = dealer;
       // table.playerToToken.put(id, new ArrayList<Token>());
        if(table.playerToToken != null) {
            table.playerToToken.put(id, new ArrayList<>());
        }
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        if (!human) createArtificialIntelligence();
        while (!terminate) {
            if(delay == 3000){
                penalty();
            }
            else if(delay == 1000){
                point();
            }
            else if(!keyPressQueue.isEmpty()){
                Integer slot = null;
                try {
                    slot = keyPressQueue.take();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }

                placeToken(slot);
            }
        }

        if (!human) try { aiThread.join(); } catch (InterruptedException ignored) {}
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                Integer slot = simulateKeyPress();
                if (!table.shouldDealerCheck) {
                    keyPressed(slot);
                }
            }
            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        keyPressQueue.clear();
        myTokensQueue.clear();
        terminate = true;
        try {
            playerThread.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        if(!human){
            aiThread.interrupt();
        }

    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        if(delay == -1 && keyPressQueue.remainingCapacity() != 0) {
            try {
                this.keyPressQueue.add(slot);
            } catch (IllegalStateException e) {
                e.printStackTrace();
            }
        }
        // TODO implement
    }


    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        score++;
        try{
            env.ui.setFreeze(id, env.config.pointFreezeMillis);
            Thread.sleep(env.config.pointFreezeMillis);
            if(!human){
                Thread.sleep(env.config.pointFreezeMillis);
            }
        }catch (InterruptedException e){
            e.printStackTrace();
        }
        env.ui.setScore(id, score);
        env.ui.setFreeze(id, 0);
        //clears token queue
        if(!myTokensQueue.isEmpty()){
            myTokensQueue.clear();
        }
        delay = -1;
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests

    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {

        for(long penaltyTimeCountdown = env.config.penaltyFreezeMillis; penaltyTimeCountdown>=0; penaltyTimeCountdown=penaltyTimeCountdown-1000) {
            try {
                Thread.sleep(env.config.pointFreezeMillis);
                if (!human) {
                    Thread.sleep(env.config.pointFreezeMillis);
                }
                env.ui.setFreeze(id, penaltyTimeCountdown);

            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        env.ui.setFreeze(id, 0);
        if(!human){
            dealer.removerTokensFromPlayer(this);
        }
        delay = -1;
        // TODO implement
    }

    public int score() {
        return score;
    }

    //added methods

    /**
     * @pre slotsQueue is not empty
     */
    public void placeToken(Integer slot){

        if(!table.shouldDealerCheck){
            if(myTokensQueue.contains(slot)){ //already has token in slot, so remove token
                table.removeToken(id,slot);
                myTokensQueue.remove(slot);
            }else if (myTokensQueue.remainingCapacity() != 0){
                try{
                    myTokensQueue.add(slot);
                    table.placeToken(id, slot);
                }catch (IllegalStateException e){
                }
            }
            if(myTokensQueue.remainingCapacity()==0){ //if queue is full
                notifyDealer();
            }
        }

    }

    public void notifyDealer(){
        //check if cards are still on table
        ArrayBlockingQueue<Token> toCheckQueue = new ArrayBlockingQueue<>(3);
        for(Integer currToken: myTokensQueue){
            if(table.slotToCard[currToken] == null){
                return;
            }
            toCheckQueue.add(new Token(id,currToken));
            //check if the cards I placed tokens on are still on the table
            if(!table.hasTokenInSlot(id, currToken)){
                return;
            }
        }
        table.setCheckQueue = toCheckQueue;
        table.shouldDealerCheck = true;
    }


    private int simulateKeyPress(){
        Random random = new Random();
        int[] availableKeys = new int[env.config.tableSize];
        int randomIndex = random.nextInt(availableKeys.length);
        return randomIndex;
    }


    public void setDelay(long i) {
        delay = i;
    }
}
