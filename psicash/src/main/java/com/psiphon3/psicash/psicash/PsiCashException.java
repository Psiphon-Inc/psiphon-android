package com.psiphon3.psicash.psicash;

import ca.psiphon.psicashlib.PsiCashLib;

public abstract class PsiCashException extends Exception {
    PsiCashException(String message) {
        super(message);
    }

    PsiCashException() {
        super();
    }

    public abstract String getUIMessage();

    static class Transaction extends PsiCashException {
        private final PsiCashLib.Status status;

        public Transaction(PsiCashLib.Status s) {
            status = s;
        }

        @Override
        public String getUIMessage() {
            String uiMessage = "An unknown error has occurred";
            switch (status) {
                case INVALID:
                    uiMessage = "Invalid response.";
                    break;
                case SUCCESS:
                    uiMessage = "Success.";
                    break;
                case EXISTING_TRANSACTION:
                    uiMessage = "You already have an active Speed Boost purchase.";
                    break;
                case INSUFFICIENT_BALANCE:
                    uiMessage = "Insufficient balance for Speed Boost purchase.";
                    break;
                case TRANSACTION_AMOUNT_MISMATCH:
                    uiMessage = "Price of Speed Boost is out of date. Updating local products.";
                    break;
                case TRANSACTION_TYPE_NOT_FOUND:
                    uiMessage = "Speed Boost product not found. Your app may be out of date. Please check for updates.";
                    break;
                case INVALID_TOKENS:
                    uiMessage = "The app has entered an invalid state. Please reinstall the app to continue using PsiCash.";
                    break;
                case SERVER_ERROR:
                    uiMessage = "Server error";
                    break;

            }
            return uiMessage;
        }
    }

    static class Critical extends PsiCashException {
        String uiMesssage = null;

        public Critical(String message) {
            super(message);
        }

        public Critical(String errorMessage, String uiMessage) {
            super(errorMessage);
            this.uiMesssage = uiMessage;
        }

        @Override
        public String getUIMessage() {
            if (uiMesssage == null) {
                return "A critical error has occurred. Please send feedback.";
            } else {
                return uiMesssage;
            }
        }
    }

    static class Recoverable extends PsiCashException {
        String uiMesssage = null;

        public Recoverable(String errorMessage, String uiMessage) {
            super(errorMessage);
            this.uiMesssage = uiMessage;
        }

        public Recoverable(String message) {
            super(message);
        }

        @Override
        public String getUIMessage() {
            if (uiMesssage == null) {
                return "A recoverable error has occurred. Please try again later.";
            } else {
                return uiMesssage;
            }
        }
    }
}
