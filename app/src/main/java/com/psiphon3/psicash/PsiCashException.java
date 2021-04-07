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

import android.content.Context;

import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.subscription.R;

import ca.psiphon.psicashlib.PsiCashLib;

public abstract class PsiCashException extends Exception {
    PsiCashException(String message) {
        super(message);
    }

    PsiCashException() {
        super();
    }

    public abstract String getUIMessage(Context ctx);

    static class Transaction extends PsiCashException {
        private final PsiCashLib.Status status;

        Transaction(PsiCashLib.Status s) {
            status = s;
        }

        PsiCashLib.Status getStatus() {
            return status;
        }

        @Override
        public String getUIMessage(Context ctx) {
            String uiMessage;
            switch (status) {
                case INVALID:
                    uiMessage = ctx.getString(R.string.psicash_transaction_invalid_message);
                    break;
                case SUCCESS:
                    uiMessage = ctx.getString(R.string.psicash_transaction_success_message);
                    break;
                case EXISTING_TRANSACTION:
                    uiMessage = ctx.getString(R.string.psicash_transaction_existing_message);
                    break;
                case INSUFFICIENT_BALANCE:
                    uiMessage = ctx.getString(R.string.psicash_transaction_insufficient_balance_message);
                    break;
                case TRANSACTION_AMOUNT_MISMATCH:
                    uiMessage = ctx.getString(R.string.psicash_transaction_amount_mismatch_message);
                    break;
                case TRANSACTION_TYPE_NOT_FOUND:
                    uiMessage = ctx.getString(R.string.psicash_transaction_type_not_found_message);
                    break;
                case INVALID_TOKENS:
                    uiMessage = ctx.getString(R.string.psicash_transaction_invalid_tokens_message);
                    break;
                case INVALID_CREDENTIALS:
                    uiMessage = ctx.getString(R.string.psicash_transaction_invalid_credentials_message);
                    break;
                case BAD_REQUEST:
                    uiMessage = ctx.getString(R.string.psicash_transaction_bad_request_message);
                    break;
                case SERVER_ERROR:
                    uiMessage = ctx.getString(R.string.transaction_server_error_message);
                    break;
                default:
                    Utils.MyLog.g("Unexpected PsiCash transaction status: " + status);
                    uiMessage = ctx.getString(R.string.unexpected_error_occured_send_feedback_message);
                    break;
            }
            return uiMessage;
        }
    }

    static class Critical extends PsiCashException {
        String uiMesssage = null;

        Critical(String message) {
            super(message);
        }

        Critical(String errorMessage, String uiMessage) {
            super(errorMessage);
            this.uiMesssage = uiMessage;
        }

        @Override
        public String getUIMessage(Context ctx) {
            if (uiMesssage == null) {
                return ctx.getString(R.string.psicash_critical_error_message);
            } else {
                return uiMesssage;
            }
        }
    }

    static class Recoverable extends PsiCashException {
        String uiMesssage = null;

        Recoverable(String errorMessage, String uiMessage) {
            super(errorMessage);
            this.uiMesssage = uiMessage;
        }

        Recoverable(String message) {
            super(message);
        }

        @Override
        public String getUIMessage(Context ctx) {
            if (uiMesssage == null) {
                return ctx.getString(R.string.psicash_recoverable_error_message);
            } else {
                return uiMesssage;
            }
        }
    }

    public static class Video extends PsiCashException {
        String uiMesssage = null;

        public Video(String message) {
            super(message);
        }

        public Video(String errorMessage, String uiMessage) {
            super(errorMessage);
            this.uiMesssage = uiMessage;
        }

        @Override
        public String getUIMessage(Context ctx) {
            if (uiMesssage == null) {
                return ctx.getString(R.string.psicash_video_not_available_message);
            } else {
                return uiMesssage;
            }
        }
    }

}
