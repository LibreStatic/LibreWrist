package com.google.android.gms.tasks;

/** Compile-time STUB (the real class lives in the device's GMS). Does NOT go into the dex. */
public class Task<TResult> {
    public Task<TResult> addOnSuccessListener(OnSuccessListener<? super TResult> listener) { return this; }
    public Task<TResult> addOnCompleteListener(OnCompleteListener<TResult> listener) { return this; }
    public Task<TResult> addOnFailureListener(OnFailureListener listener) { return this; }
    public boolean isSuccessful() { return false; }
    public TResult getResult() { return null; }
    public Exception getException() { return null; }
}
