package bguspl.set.ex;

import bguspl.set.Env;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    //added fields


    public Integer[] slotsToRemove = new Integer[3];


    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        for(int i= 0; i< players.length; i++){
            Thread playerT= new Thread(players[i],"player-" + (i+1));
            playerT.start();
        }
        while (!shouldFinish()) {
            Collections.shuffle(deck);
            placeCardsOnTable();
            timerLoop();
            updateTimerDisplay(false);
            removeAllCardsFromTable();
        }
        announceWinners();
        terminate();
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     * needs to add another thread that counts 60 seconds or goes to sleep for 60 second, together with reshuffle time,
     * this thread's job is to notify dealer that the reshuffle time has came because without him, dealer doesn't get notified
     * that the time has passed.
     */
    private void timerLoop() {
        updateTimerDisplay(true);
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            updateTimerDisplay(false);
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            placeCardsOnTable();
        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        for (Player player: players)
        {
            player.terminate();
        }
        terminate = true;
        Thread.currentThread().interrupt();
        //dealer's terminate should call all terminates.
        // TODO implement
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *`
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        if(!terminate){
            for(int i = 0; i<slotsToRemove.length; i++){
                if(slotsToRemove[i] != null){
                    table.removeCard(slotsToRemove[i]);
                }
            }
        }

        // TODO implement
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        if(!terminate) {
            int cards = table.countCards();
            if (cards < 12) {
                int toPlace = Math.min(12 - cards, deck.size());
                for (int i = 0; i < toPlace; i++) {
                    int card = deck.remove(0);
                    int firstEmptySlot = table.findFirstEmptySlot();
                    if (firstEmptySlot != -1) {
                        table.placeCard(card, firstEmptySlot);
                    }
                }
            }
            if (env.config.hints) {
                table.hints();
            }
        }

    }


    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     * should be woken if: terminate, timeout reached, provoked by player
     */
    private void sleepUntilWokenOrTimeout() {
        if(!terminate) {
            while (!table.shouldDealerCheck && System.currentTimeMillis() < reshuffleTime) {
                try {
                    if(reshuffleTime - System.currentTimeMillis() > env.config.turnTimeoutWarningMillis){
                        Thread.sleep(900);
                        updateTimerDisplay(false);
                    }
                    else{
                        Thread.sleep(100);
                        updateTimerDisplay(false);
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            if (table.shouldDealerCheck) {
                synchronized (table.shouldDealerCheck) {
                    for (Player currplayer : players) {
                        if (table.setCheckQueue.peek() != null && currplayer.id == table.setCheckQueue.peek().getPlayerId()) {
                            checkSet(currplayer);
                            break;
                        }
                    }
                }
            } else {
                removeAllCardsFromTable();
            }
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        long timerDisplay = 0 ;
        if(reset) {
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
            timerDisplay = env.config.turnTimeoutMillis;
        }
        else {
            timerDisplay = reshuffleTime - System.currentTimeMillis();
        }
        env.ui.setCountdown(timerDisplay, timerDisplay < env.config.turnTimeoutWarningMillis);
    }


    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        if(!terminate) {
            for (int i = 0; i < table.slotToCard.length; i++) {
                if (table.slotToCard[i] != null) {
                    deck.add(table.slotToCard[i]);
                    table.removeCard(i);
                }
            }
            if (env.util.findSets(deck, 1).size() == 0) {
                terminate();
            }
            updateTimerDisplay(true);
            removeAllTokens();
        }

        // TODO implement

    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
            List<Player> winningPlayers = new ArrayList<>();
            Player winningPlayer = players[0];
            for (Player player : players) {
                if (player.score() > winningPlayer.score()) {
                    winningPlayer = player;
                }
            }
            for (Player player : players) {
                if (player.score() == winningPlayer.score()) {
                    winningPlayers.add(player);
                }
            }
            int[] winningPlayersArray = new int[winningPlayers.size()];
            for (int i = 0; i < winningPlayers.size(); i++) {
                winningPlayersArray[i] = winningPlayers.get(i).id;
            }
            env.ui.announceWinner(winningPlayersArray);
    }

    /**
     * Notifies the dealer that a player placed 3 tokens.
     *
     * @param player - the player that placed 3 tokens.
     */
    public void checkSet(Player player) {
        if(!terminate) {
            int[] cards = new int[3];
            Integer[] slots = new Integer[3];
            int counter = 0;
            List<Integer> playerTokens = table.playerToToken.get(player.id);
            for (Integer slot : playerTokens) {
                slots[counter] = slot;
                if (table.slotToCard[slot] != null) {
                    int currCard = table.slotToCard[slot];
                    cards[counter] = currCard;
                    counter++;
                }
            }
            if (env.util.testSet(cards)) {
                slotsToRemove = slots;
                removeTokensFromPlayer();
                removeCardsFromTable();
                givePoint(player);
                placeCardsOnTable();
                updateTimerDisplay(true);
            } else {
                givePenalty(player);

            }
            table.shouldDealerCheck = false;
        }
    }

    private void givePenalty(Player player) {
        player.setDelay(3000);
    }

    private void givePoint(Player player) {
        player.setDelay(1000);
    }

    private void removeTokensFromPlayer() {
        for (Player currPlayer : players) {
            for (Integer currSlotToRemove : slotsToRemove) {
                if (currSlotToRemove != null) {
                    currPlayer.myTokensQueue.remove(currSlotToRemove);
                    table.removeToken(currPlayer.id, currSlotToRemove);
                }
            }
        }
    }
    public void removerTokensFromPlayer(Player player){
        for(Integer tokenToRemove: player.myTokensQueue){
            player.myTokensQueue.remove(tokenToRemove);
            table.removeToken(player.id, tokenToRemove);
        }
    }

    private void removeAllTokens(){
        for(Player currPlayer: players){
            for(int currTokenSlot :currPlayer.myTokensQueue){
                table.removeToken(currPlayer.id, currTokenSlot);
            }
            currPlayer.myTokensQueue.clear();
        }
    }
}
