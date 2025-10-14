package com.unmojang.loki.transformers;

import com.unmojang.loki.Premain;
import nilloader.api.lib.mini.MiniTransformer;
import nilloader.api.lib.mini.PatchContext;
import nilloader.api.lib.mini.annotation.Patch;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.*;
import java.util.*;

@Patch.Class("net.minecraft.client.Minecraft") // early-loaded class
public class URLFactoryTransformer extends MiniTransformer {
	private static final Set<String> ALLOWED_DOMAINS; // TODO use these
	private static final Set<String> EXACT_PATHS; // and these
	private static final Map<String, String> YGGDRASIL_MAP;

	static {
		ALLOWED_DOMAINS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
				"s3.amazonaws.com",
				"www.minecraft.net",
				"skins.minecraft.net",
				"session.minecraft.net"
		)));
		EXACT_PATHS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
				"/foo/bar",
				"/baz"
		)));
		Map<String, String> tmp = new HashMap<>();
		tmp.put("authserver.mojang.com", System.getProperty("minecraft.api.auth.host", "authserver.mojang.com"));
		tmp.put("api.mojang.com", System.getProperty("minecraft.api.account.host", "api.mojang.com"));
		tmp.put("sessionserver.mojang.com", System.getProperty("minecraft.api.session.host", "sessionserver.mojang.com"));
		YGGDRASIL_MAP = Collections.unmodifiableMap(tmp);
	}

	@Patch.Method("<clinit>()V")
	public void patchClinit(PatchContext ctx) {
		ctx.jumpToLastReturn();
		ctx.add(INVOKESTATIC("com/unmojang/loki/transformers/URLFactoryTransformer$Hooks",
				"installFactory", "()V"));
	}

	public static class Hooks {

		public static void installFactory() {
			final URLStreamHandlerFactory ourFactory = protocol -> {
                URLStreamHandler existing = null;
                try {
                    URLStreamHandler system = getDefaultHandler(protocol);
                    if (system == null) {
                        return null; // let JVM handle it
                    }
                    return wrapHandler(system);
                } catch (Throwable t) {
                    Premain.log.warn("failed to create/wrap handler for protocol: " + protocol, t);
                    return null;
                }
            };

			try {
				URL.setURLStreamHandlerFactory(ourFactory);
				Premain.log.info("setURLStreamHandlerFactory succeeded");
				return;
			} catch (Error e) {
				Premain.log.info("setURLStreamHandlerFactory threw Error (already set). Will try reflective wrap.");
			} catch (Throwable t) {
				Premain.log.error("Unexpected error setting URLStreamHandlerFactory", t);
				return;
			}

			// If we got here, factory was already set. Try to wrap the existing factory reflectively.
			try {
				Field factoryField = URL.class.getDeclaredField("factory");
				factoryField.setAccessible(true);
				URLStreamHandlerFactory existingFactory = (URLStreamHandlerFactory) factoryField.get(null);

				if (existingFactory == null) {
					try {
						URL.setURLStreamHandlerFactory(ourFactory);
						Premain.log.info("setURLStreamHandlerFactory succeeded on second attempt");
						return;
					} catch (Throwable t) {
						Premain.log.warn("Second attempt to set factory failed", t);
					}
				}

				final URLStreamHandlerFactory delegateFactory = existingFactory;
				URLStreamHandlerFactory wrapper = new URLStreamHandlerFactory() {
					@Override
					public URLStreamHandler createURLStreamHandler(final String protocol) {
						try {
							// Ask the existing factory first (if present)
							URLStreamHandler handler = null;
							if (delegateFactory != null) {
								try {
									handler = delegateFactory.createURLStreamHandler(protocol);
								} catch (Throwable t) {
									Premain.log.info("existing factory threw for protocol " + protocol, t);
								}
							}
							// If existing factory returned null, try system default
							if (handler == null) handler = getDefaultHandler(protocol);
							if (handler == null) return null;
							return wrapHandler(handler);
						} catch (Throwable t) {
							Premain.log.warn("Failed to create wrapped handler for: " + protocol, t);
							return null;
						}
					}
				};

				// Replace the private factory field with our wrapper (dangerous but necessary if factory already set).
				factoryField.set(null, wrapper);
				Premain.log.info("Replaced URL.factory reflectively with wrapper factory.");

			} catch (Throwable t) {
				Premain.log.error("Failed to wrap existing URL.factory reflectively", t);
			}
		}

		private static URLStreamHandler wrapHandler(final URLStreamHandler delegate) {
			return new URLStreamHandler() {
				@Override
				protected URLConnection openConnection(URL u) throws IOException {
					String protocol = u.getProtocol();
					if (!"http".equals(protocol) && !"https".equals(protocol)) { // not a http(s) request; ignore
						return openDefault(delegate, u);
					}
					return wrapConnection(u, openDefault(delegate, u));
				}

				@Override
				protected URLConnection openConnection(URL u, Proxy proxy) throws IOException {
					String protocol = u.getProtocol();
					if (!"http".equals(protocol) && !"https".equals(protocol)) {  // not a http(s) request; ignore
						return openDefault(delegate, u);
					}
					return wrapConnection(u, openDefault(delegate, u));
				}
			};
		}

		private static URLStreamHandler getDefaultHandler(String protocol) {
			try {
				// Use reflection to create the default handler
				Class<?> cls = Class.forName("sun.net.www.protocol." + protocol + ".Handler");
				return (URLStreamHandler) cls.newInstance();
			} catch (Exception e) {
				return null;
			}
		}

		private static URLConnection wrapConnection(URL originalUrl, URLConnection originalConn) {
			String host = originalUrl.getHost();
			if (YGGDRASIL_MAP.containsKey(host)) { // yggdrasil
				String replacement = YGGDRASIL_MAP.get(host);
				try {
					// parse replacement
					URL replacementUrl = new URL(replacement.startsWith("http") ? replacement
							: originalUrl.getProtocol() + "://" + replacement);

					// append original path
					String newPath = replacementUrl.getPath();
					if (!newPath.endsWith("/") && !originalUrl.getPath().isEmpty()) {
						newPath += "/";
					}
					newPath += originalUrl.getPath().startsWith("/") ? originalUrl.getPath().substring(1) : originalUrl.getPath();

					// append query if present
					String finalUrlStr = replacementUrl.getProtocol() + "://" + replacementUrl.getHost();
					if (replacementUrl.getPort() != -1) {
						finalUrlStr += ":" + replacementUrl.getPort();
					}
					finalUrlStr += newPath;
					if (originalUrl.getQuery() != null && !originalUrl.getQuery().isEmpty()) {
						finalUrlStr += "?" + originalUrl.getQuery();
					}

					final URL targetUrl = new URL(finalUrlStr);
					Premain.log.info("Redirecting " + originalUrl + " -> " + targetUrl);

					final URLConnection targetConn = targetUrl.openConnection();

					return new HttpURLConnection(originalUrl) {
						@Override
						public void connect() throws IOException {
							targetConn.connect();
						}

						@Override
						public InputStream getInputStream() throws IOException {
							return targetConn.getInputStream();
						}

						@Override
						public int getResponseCode() throws IOException {
							if (targetConn instanceof HttpURLConnection) {
								return ((HttpURLConnection) targetConn).getResponseCode();
							}
							return 200;
						}

						@Override
						public String getResponseMessage() throws IOException {
							if (targetConn instanceof HttpURLConnection) {
								return ((HttpURLConnection) targetConn).getResponseMessage();
							}
							return "OK";
						}

						@Override
						public void disconnect() {
							if (targetConn instanceof HttpURLConnection) {
								((HttpURLConnection) targetConn).disconnect();
							}
						}

						@Override
						public boolean usingProxy() {
							return false;
						}
					};

				} catch (Exception e) {
					Premain.log.warn("Failed to redirect " + originalUrl + ": " + e);
					return originalConn;
				}
			}

			return originalConn;
		}


		private static URLConnection openDefault(URLStreamHandler handler, URL url) throws IOException {
			try {
				java.lang.reflect.Method m = URLStreamHandler.class
						.getDeclaredMethod("openConnection", URL.class);
				m.setAccessible(true);
				return (URLConnection) m.invoke(handler, url);
			} catch (Exception e) {
				throw new IOException(e);
			}
		}

		private static URLConnection openDefault(URLStreamHandler handler, URL url, Proxy proxy) throws IOException {
			try {
				java.lang.reflect.Method m = URLStreamHandler.class
						.getDeclaredMethod("openConnection", URL.class, Proxy.class);
				m.setAccessible(true);
				return (URLConnection) m.invoke(handler, url, proxy);
			} catch (Exception e) {
				throw new IOException(e);
			}
		}
	}
}
