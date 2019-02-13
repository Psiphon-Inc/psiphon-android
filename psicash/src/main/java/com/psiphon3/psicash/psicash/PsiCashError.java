package com.psiphon3.psicash.psicash;

import ca.psiphon.psicashlib.PsiCashLib;

interface PsiCashError {
    String getUIMessage();

    class TransactionError extends RuntimeException implements PsiCashError {
        private PsiCashLib.Status status;

        public TransactionError(PsiCashLib.Status s) {
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

    class CriticalError extends RuntimeException implements PsiCashError {
        String uiMesssage = null;

        public CriticalError(String message) {
            super(message);
        }

        public CriticalError(String errorMessage, String uiMessage) {
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

    class RecoverableError extends RuntimeException implements PsiCashError {
        String uiMesssage = null;

        public RecoverableError(String errorMessage, String uiMessage) {
            super(errorMessage);
            this.uiMesssage = uiMessage;
        }

        public RecoverableError(String message) {
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
