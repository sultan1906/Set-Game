package bguspl.set.ex;

import bguspl.set.Env;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;


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

    protected int playersAmount;

    private final Thread[] playerThread;

    private final int SET_SIZE = 3;

    private final ArrayBlockingQueue<Player> playerSet;

    private final Object lock;

    private final Object lockPlayer;


    public Player[] getPlayers() {
        return players;
    }

    public ArrayBlockingQueue<Player> getPlayerSet() {
        return playerSet;
    }

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());

        this.playersAmount = players.length;
        this.playerThread = new Thread[playersAmount];
        this.playerSet = new ArrayBlockingQueue<>(players.length);
        this.lock = new Object();
        this.lockPlayer = new Object();
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("Thread " + Thread.currentThread().getName() + " starting.");
        placeCardsOnTable();
        for (int i = 0; i < playersAmount; i++) {
            playerThread[i] = new Thread(players[i]);
            playerThread[i].start();
        }
        while (!shouldFinish()) {
            timerLoop();
            updateTimerDisplay(true);
            synchronized (table) {
                removeAllCardsFromTable();
                if (!terminate)
                    placeCardsOnTable();
            }
        }
        terminate();
        removeAllCardsFromTable();
        announceWinners();
        env.logger.info("Thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis + 999;
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            updateTimerDisplay(false);
            sleepUntilWokenOrTimeout();
            Player playerId;
            synchronized (playerSet) {
                if (!playerSet.isEmpty()) {
                    playerId = playerSet.remove();
                    isValidSet (playerId.getId());
                }
            }
        }
    }

    public void isValidSet (int id) {
        Player player = players[id];
        List<Integer> placedCards = player.getSelectedCardsOnTable();
        synchronized (lockPlayer) {
            if(checkNumOfCards(player, placedCards))
                return;
            else checkSet(player, placedCards);
            player.releasePlayer();
        }
    }


    public boolean checkNumOfCards(Player player, List<Integer> placedCards) {
        if (placedCards.size() != SET_SIZE) {
            player.setSetOrNot(2); // Card was taken
            player.releasePlayer();
            return true;
        }
        return false;
    }

    public void checkSet(Player player, List<Integer> placedCards){
        int[] cards = IntStream.range(0, SET_SIZE)
                .map(i -> table.slotToCard[placedCards.get(i)])
                .toArray();
        if (env.util.testSet(cards)) {
            player.setSetOrNot(1); // Is a set
            removeCardsFromTable(placedCards.stream().mapToInt(Integer::intValue).toArray());
            placeCardsOnTable();
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis + 999;
        } else {
            player.setSetOrNot(0); // Not a set
        }
    }


    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        for (int i = 0; i < players.length; i++) {
                players[i].terminate();
                players[i].setSetOrNot(2);
                playerThread[i].interrupt();
               try {
                   playerThread[i].join();
               } catch (InterruptedException e) {
                   System.out.println(e.getMessage());
               }

        }
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).isEmpty();
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable(int[] slots) {
        synchronized (table) {
            for (int i = 0; i < SET_SIZE; i++) {
                table.removeCard(slots[i], players);
            }
        }
        if (shouldFinish())
            terminate = true;
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    protected void placeCardsOnTable() {
        Random rand = new Random();
        for (int i = 0; i < env.config.tableSize; i++) {
            if (table.slotToCard[i] == null) {
                if (!deck.isEmpty()) {
                    int deckOut = rand.nextInt(deck.size());
                    int putCard = deck.get(deckOut);
                    table.placeCard(putCard, i);
                    deck.remove(deckOut);
                }
            }
        }
        table.canPress = true;
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        synchronized (lock) {
            try {
                lock.wait(10);
            } catch (InterruptedException e) {
                System.out.println(" dealer was interrupted from sleepUntil");
            }
        }
    }

    public void wakeDealer() {
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if (reset) {
            env.ui.setCountdown(env.config.turnTimeoutMillis + 999, false);
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis + 999;
        } else env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), reshuffleTime - System.currentTimeMillis() < env.config.turnTimeoutWarningMillis);
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        for (int i = 0; i < env.config.tableSize; i++) {
            if (table.slotToCard[i] != null) {
                int temp = table.slotToCard[i];
                if (!terminate)
                    deck.add(temp);
                table.removeCard(i, players);
            }
            for (int j = 0; j < playersAmount; j++) {
                table.removeToken(players[j].getId(), i, players);
            }
        }
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int max = 0;
        int count = 0;
        for (int i = 0; i < playersAmount; i++) {
            if (players[i].score() > max) {
                max = players[i].score();
                count = 0;
            }
            if (players[i].score() == max)
                count++;
        }
        int[] winners = new int[count];
        int j = 0;
        for (int i = 0; i < playersAmount; i++) {
            if (players[i].score() == max) {
                winners[j] = players[i].getId();
                j++;
            }
        }
        env.ui.announceWinner(winners);
    }
}
