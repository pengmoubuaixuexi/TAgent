package cn.bugstack.ai.domain.agent.service.rag;

/** A safe, user-facing storage error; backend details remain in the exception cause. */
public class RagStorageException extends RuntimeException {
    public RagStorageException(String message) { super(message); }
    public RagStorageException(String message, Throwable cause) { super(message, cause); }
}
