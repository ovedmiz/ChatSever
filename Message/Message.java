package Message;

public interface Message<K, D> {
    public K getKey();
    public D getData();
}