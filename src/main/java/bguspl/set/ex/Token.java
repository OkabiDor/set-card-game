package bguspl.set.ex;


public class Token{

    private int id;
    private int slot;

    public Token(int id, int slot){
        this.id = id;
        this.slot = slot;
    }

    public Token(int id){
        this.id = id;
        slot = -1;
    }

    public int getPlayerId() {
        return id;
    }

    public int getSlot() {
        return slot;
    }

}