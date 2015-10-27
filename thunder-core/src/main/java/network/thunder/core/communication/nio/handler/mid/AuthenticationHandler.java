/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package network.thunder.core.communication.nio.handler.mid;

import com.google.gson.Gson;
import io.netty.channel.*;
import network.thunder.core.communication.Message;
import network.thunder.core.mesh.Node;
import network.thunder.core.communication.Type;
import network.thunder.core.communication.objects.subobjects.AuthenticationObject;
import org.bitcoinj.core.ECKey;

import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;

/*
 * FLOW:
 * client send auth
 * server send auth
 */
public class AuthenticationHandler extends ChannelDuplexHandler {

    private Node node;
    private boolean isServer = false;

    private ECKey keyServer;

    private int authFailCounter = 0;
    private static final int AUTH_FAIL_MAX_RETRIES = 3;

    public ECKey keyTempClient;
    public ECKey keyTempServer;

    public AuthenticationHandler (ECKey key, boolean isServer, Node node) {
        this.keyServer = key;
        this.isServer = isServer;
        this.node = node;

    }

    @Override
    public void channelActive (final ChannelHandlerContext ctx) {

		/* I don't like this design, as it wires the EncryptionHandler and the AuthenticationHandler together for eternity, but I guess that is per
         * product design....
		 *
		 * TODO: Merge Encryption and Authentication handler, as authentication is no longer possible without encryption..
		 */
        this.keyTempClient = node.getPubKeyTempClient();
        this.keyTempServer = node.getPubKeyTempServer();

        System.out.println("CHANNEL ACTIVE AUTHENTICATION");
        //The node receiving the incoming connection sends out his auth first
        if (!isServer) {
            sendAuthentication(ctx);
        }
//		ctx.fireChannelActive();

    }

    public void sendAuthentication (ChannelHandlerContext ctx) {
        try {
            ctx.writeAndFlush(new Message(node.getAuthenticationObject(keyServer, keyTempClient), Type.AUTH_SEND)).sync().addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete (ChannelFuture future) throws Exception {
                    System.out.println(future);
                }
            });
            if (node.isAuthFinished()) {
                ctx.fireChannelActive();
            }
        } catch (InterruptedException | NoSuchAlgorithmException | NoSuchProviderException e) {
            e.printStackTrace();
        }
    }

    public void sendFailure (ChannelHandlerContext ctx) {
        ctx.writeAndFlush(new Message(null, Type.FAILURE));
    }

    public void authenticationFinished (ChannelHandlerContext ctx) {
        System.out.println("Authentication Finished!");
        node.setNettyContext(ctx);

        ctx.fireChannelActive();
    }

    @Override
    public void channelRead (ChannelHandlerContext ctx, Object msg) throws Exception {
        if (node.isAuthFinished()) {
            //Authentication is complete, we don't touch any messages here anymore.
            ctx.fireChannelRead(msg);
        } else {
            //Check authentication first before doing anything else with the messages.
            Message message = (Message) msg;

//			System.out.println(message.type);

            if (message.type >= 1010 && message.type <= 1099) {

                if (message.type == Type.AUTH_SEND) {
                    AuthenticationObject authObject = new Gson().fromJson(message.data, AuthenticationObject.class);
                    if (node.processAuthentication(authObject, ECKey.fromPublicOnly(authObject.pubkeyServer), keyTempServer)) {

                        if (!node.hasSentAuth()) {
                            sendAuthentication(ctx);
                        } else {
                            authenticationFinished(ctx);
                        }

                    }
                } else if (message.type == Type.AUTH_FAILED) {
                    //For some reason authentication failed. We will retry it some times.
                    if (authFailCounter < AUTH_FAIL_MAX_RETRIES) {
                        sendAuthentication(ctx);
                        authFailCounter++;
                    } else {
                        //TODO: Probably some more stuff here like blacklisting or anything?
                        ctx.disconnect();
                    }
                }

            } else if (message.type == 0) {
                System.out.println("Got Failure:");
                System.out.println(message);
            } else {
                //Not authenticated. Will not look at messages with wrong types.
                System.out.println("Not authenticated!");
                sendFailure(ctx);
            }

        }

    }

    @Override
    public void write (ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        //Make sure to not send messages accidentally when auth is not finished yet.
//		Message message = (Message) msg;
//		if (!node.isAuthFinished() && (message.type < 1010 || message.type > 1099)) {
//			throw new RuntimeException("This should not happen. Don't send messages when auth is not finished yet");
//		}

        ctx.writeAndFlush(msg, promise);
    }

    @Override
    public void exceptionCaught (ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }
}
