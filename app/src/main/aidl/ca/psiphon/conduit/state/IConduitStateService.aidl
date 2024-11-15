package ca.psiphon.conduit.state;

import ca.psiphon.conduit.state.IConduitStateCallback;

// Interface to register for conduit state updates
interface IConduitStateService {
    void registerClient(IConduitStateCallback callback);
    void unregisterClient(IConduitStateCallback callback);
}
