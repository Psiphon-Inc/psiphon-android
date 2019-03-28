/*
 *
 * Copyright (c) 2019, Psiphon Inc.
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.psiphon3.psicash;

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

        Transaction(PsiCashLib.Status s) {
            status = s;
        }

        PsiCashLib.Status getStatus() {
            return status;
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
                    uiMessage = "You have an active Speed Boost purchase.";
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
