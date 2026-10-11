package cn.bugstack.ai.domain.agent.service.execute.common;

import io.modelcontextprotocol.client.McpSyncClient;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Counts connecting, active and retired-in-flight generations. No network operations under its monitor. */
final class McpConnectionPool {
    static final class Slot {
        final String id,owner; final long generation; McpSyncClient client;
        int leases; long lastUsed; boolean retired;
        Slot(String id,String owner,long generation,long now){this.id=id;this.owner=owner;this.generation=generation;lastUsed=now;}
    }
    private final Set<Slot> slots=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<String,Slot> current=new HashMap<>();private long generation;
    synchronized Slot reserve(String id,String owner,int globalLimit,int ownerLimit) {
        if(current.containsKey(id))throw new IllegalStateException("MCP connection is already reserved");
        if(slots.size()>=globalLimit||slots.stream().filter(s->s.owner.equals(owner)).count()>=ownerLimit)
            throw new McpCapacityException("MCP 连接容量已满，请等待空闲连接回收后重试");
        Slot slot=new Slot(id,owner,++generation,System.currentTimeMillis());slots.add(slot);current.put(id,slot);return slot;
    }
    synchronized void commit(Slot slot,McpSyncClient client) {
        if(!slots.contains(slot)||slot.retired||current.get(slot.id)!=slot)throw new IllegalStateException("MCP connection was revoked during creation");
        slot.client=Objects.requireNonNull(client);
    }
    synchronized Slot current(String id){return current.get(id);}
    synchronized boolean isCurrent(String id,McpSyncClient client){Slot slot=current.get(id);return slot!=null&&!slot.retired&&slot.client==client;}
    synchronized List<String> idleCandidates(long cutoff){return current.values().stream().filter(s->s.client!=null&&s.leases==0&&s.lastUsed<=cutoff).sorted(Comparator.comparingLong(s->s.lastUsed)).map(s->s.id).toList();}
    synchronized Lease acquire(String id,McpSyncClient expected,boolean business) {
        Slot slot=current.get(id);
        if(slot==null||slot.retired||slot.client==null||slot.client!=expected)throw new IllegalStateException("MCP connection generation is unavailable");
        slot.leases++;if(business)slot.lastUsed=System.currentTimeMillis();return new Lease(slot);
    }
    synchronized Lease acquireCurrent(String id,boolean business) {
        Slot slot=current.get(id);return slot==null||slot.client==null?null:acquire(id,slot.client,business);
    }
    /** Returns a closeable generation only when its final in-flight operation has finished. */
    synchronized McpSyncClient retire(String id,McpSyncClient expected,boolean idleOnly) {
        Slot slot=current.get(id);if(slot==null||slot.client!=expected||idleOnly&&slot.leases>0)return null;
        slot.retired=true;current.remove(id,slot);
        if(slot.leases==0){slots.remove(slot);return slot.client;}return null;
    }
    synchronized McpSyncClient retireIfIdle(String id,McpSyncClient expected,long cutoff){Slot slot=current.get(id);return slot==null||slot.lastUsed>cutoff?null:retire(id,expected,true);}
    synchronized void abort(Slot slot){slot.retired=true;current.remove(slot.id,slot);if(slot.leases==0)slots.remove(slot);}
    synchronized boolean hasCapacity(String owner,int globalLimit,int ownerLimit){return slots.size()<globalLimit&&slots.stream().filter(s->s.owner.equals(owner)).count()<ownerLimit;}
    synchronized int activeLeases(String id){return slots.stream().filter(s->s.id.equals(id)).mapToInt(s->s.leases).sum();}
    synchronized Long lastUsed(String id){Slot slot=current.get(id);return slot==null?null:slot.lastUsed;}
    final class Lease implements AutoCloseable {
        final Slot slot;private final AtomicBoolean closed=new AtomicBoolean();
        Lease(Slot slot){this.slot=slot;}
        McpSyncClient client(){return slot.client;}
        @Override public void close(){
            if(!closed.compareAndSet(false,true))return;McpSyncClient close=null;
            synchronized(McpConnectionPool.this){slot.leases--;if(slot.retired&&slot.leases==0){slots.remove(slot);close=slot.client;}}
            closeClient(close);
        }
    }
    static void closeClient(McpSyncClient client){if(client!=null)try{client.close();}catch(RuntimeException ignored){}}
    static final class McpCapacityException extends IllegalStateException {McpCapacityException(String message){super(message);}}
}
