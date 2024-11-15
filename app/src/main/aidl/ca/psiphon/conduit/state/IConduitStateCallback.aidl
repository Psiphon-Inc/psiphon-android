package ca.psiphon.conduit.state;

// Callback interface for receiving conduit state updates
interface IConduitStateCallback {
    void onStateUpdate(String stateJson);
}
