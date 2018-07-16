package com.tonikelope.megabasterd;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.CancellationException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.crypto.CipherInputStream;
import javax.crypto.NoSuchPaddingException;
import static com.tonikelope.megabasterd.MiscTools.*;
import static com.tonikelope.megabasterd.CryptTools.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;

/**
 *
 * @author tonikelope
 */
public class ChunkUploaderMono extends ChunkUploader {

    public ChunkUploaderMono(Upload upload) {
        super(1, upload);
    }

    @Override
    public void run() {

        Logger.getLogger(getClass().getName()).log(Level.INFO, "{0} ChunkUploaderMONO {1} hello! {2}", new Object[]{Thread.currentThread().getName(), getId(), getUpload().getFile_name()});

        try (RandomAccessFile f = new RandomAccessFile(getUpload().getFile_name(), "r");) {

            String worker_url = getUpload().getUl_url();

            OutputStream out = null;

            int conta_error = 0, reads, http_status, tot_bytes_up = -1;

            boolean error = false;

            HttpURLConnection con = null;

            while (!isExit() && !getUpload().isStopped()) {

                Chunk chunk = new Chunk(getUpload().nextChunkId(), getUpload().getFile_size(), null);

                f.seek(chunk.getOffset());

                byte[] buffer = new byte[MainPanel.DEFAULT_BYTE_BUFFER_SIZE];

                do {

                    chunk.getOutputStream().write(buffer, 0, f.read(buffer, 0, Math.min((int) (chunk.getSize() - chunk.getOutputStream().size()), buffer.length)));

                } while (!isExit() && !getUpload().isStopped() && chunk.getOutputStream().size() < chunk.getSize());

                if (tot_bytes_up == -1 || error) {

                    URL url = new URL(worker_url + "/" + chunk.getOffset());

                    if (MainPanel.isUse_proxy()) {

                        con = (HttpURLConnection) url.openConnection(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(MainPanel.getProxy_host(), MainPanel.getProxy_port())));

                        if (MainPanel.getProxy_user() != null && !"".equals(MainPanel.getProxy_user())) {

                            con.setRequestProperty("Proxy-Authorization", "Basic " + MiscTools.Bin2BASE64((MainPanel.getProxy_user() + ":" + MainPanel.getProxy_pass()).getBytes()));
                        }
                    } else {

                        con = (HttpURLConnection) url.openConnection();
                    }

                    con.setRequestMethod("POST");

                    con.setDoOutput(true);

                    con.setFixedLengthStreamingMode(getUpload().getFile_size() - chunk.getOffset());

                    con.setConnectTimeout(Upload.HTTP_TIMEOUT);

                    con.setReadTimeout(Upload.HTTP_TIMEOUT);

                    con.setRequestProperty("User-Agent", MainPanel.DEFAULT_USER_AGENT);

                    out = new ThrottledOutputStream(con.getOutputStream(), getUpload().getMain_panel().getStream_supervisor());

                }

                tot_bytes_up = 0;

                error = false;

                try {

                    if (!isExit() && !getUpload().isStopped()) {

                        try (CipherInputStream cis = new CipherInputStream(chunk.getInputStream(), genCrypter("AES", "AES/CTR/NoPadding", getUpload().getByte_file_key(), forwardMEGALinkKeyIV(getUpload().getByte_file_iv(), chunk.getOffset())))) {

                            Logger.getLogger(getClass().getName()).log(Level.INFO, "{0} Uploading chunk {1} from worker {2}...", new Object[]{Thread.currentThread().getName(), chunk.getId(), getId()});

                            while (!isExit() && !getUpload().isStopped() && (reads = cis.read(buffer)) != -1 && out != null) {
                                out.write(buffer, 0, reads);

                                getUpload().getPartialProgress().add(reads);

                                getUpload().getProgress_meter().secureNotify();

                                tot_bytes_up += reads;

                                if (getUpload().isPaused() && !getUpload().isStopped()) {

                                    getUpload().pause_worker_mono();

                                    secureWait();

                                } else if (!getUpload().isPaused() && getUpload().getMain_panel().getUpload_manager().isPaused_all()) {

                                    getUpload().pause();

                                    getUpload().pause_worker_mono();

                                    secureWait();
                                }
                            }

                            out.flush();

                        }

                        if (!getUpload().isStopped()) {

                            if (tot_bytes_up < chunk.getSize()) {

                                if (tot_bytes_up > 0) {

                                    getUpload().getPartialProgress().add(-1 * tot_bytes_up);

                                    getUpload().getProgress_meter().secureNotify();
                                }

                                error = true;
                            }

                            if (error && !getUpload().isStopped()) {

                                Logger.getLogger(getClass().getName()).log(Level.WARNING, "{0} Uploading chunk {1} FAILED!...", new Object[]{Thread.currentThread().getName(), chunk.getId()});

                                getUpload().rejectChunkId(chunk.getId());

                                conta_error++;

                                if (!isExit()) {

                                    setError_wait(true);

                                    Thread.sleep(getWaitTimeExpBackOff(conta_error) * 1000);

                                    setError_wait(false);
                                }

                            } else if (!error && chunk.getOffset() + tot_bytes_up < getUpload().getFile_size()) {

                                Logger.getLogger(getClass().getName()).log(Level.INFO, "{0} Worker {1} has uploaded chunk {2}", new Object[]{Thread.currentThread().getName(), getId(), chunk.getId()});

                                Logger.getLogger(getClass().getName()).log(Level.INFO, "{0} {1} {2}", new Object[]{chunk.getOffset(), tot_bytes_up, getUpload().getFile_size()});

                                conta_error = 0;
                            }
                        }

                    } else if (isExit()) {

                        getUpload().rejectChunkId(chunk.getId());
                    }

                } catch (IOException ex) {

                    Logger.getLogger(getClass().getName()).log(Level.WARNING, "{0} Uploading chunk {1} FAILED!...", new Object[]{Thread.currentThread().getName(), chunk.getId()});

                    error = true;

                    getUpload().rejectChunkId(chunk.getId());

                    if (tot_bytes_up > 0) {

                        getUpload().getPartialProgress().add(-1 * tot_bytes_up);

                        getUpload().getProgress_meter().secureNotify();
                    }

                    Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);

                } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException | InvalidAlgorithmParameterException | InterruptedException ex) {
                    Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);

                }

                if (!error && chunk.getOffset() + tot_bytes_up == getUpload().getFile_size()) {

                    Logger.getLogger(getClass().getName()).log(Level.INFO, "{0} Worker {1} has uploaded chunk {2}", new Object[]{Thread.currentThread().getName(), getId(), chunk.getId()});

                    Logger.getLogger(getClass().getName()).log(Level.INFO, "{0} {1} {2}", new Object[]{chunk.getOffset(), tot_bytes_up, getUpload().getFile_size()});

                    Logger.getLogger(getClass().getName()).log(Level.INFO, "{0} has finished uploading all chunks. Waiting for completion handle...", new Object[]{Thread.currentThread().getName()});

                    try {

                        if ((http_status = con.getResponseCode()) != 200) {

                            throw new IOException("UPLOAD FAILED! (HTTP STATUS: " + http_status + ")");

                        } else {

                            if (out != null) {
                                out.flush();
                                out.close();
                                out = null;

                            }

                            String httpresponse = null;

                            InputStream is = con.getInputStream();

                            try (ByteArrayOutputStream byte_res = new ByteArrayOutputStream()) {

                                while ((reads = is.read(buffer)) != -1) {

                                    byte_res.write(buffer, 0, reads);
                                }

                                httpresponse = new String(byte_res.toByteArray());

                            }

                            if (httpresponse.length() > 0) {

                                if (MegaAPI.checkMEGAError(httpresponse) != 0) {

                                    throw new IOException("UPLOAD FAILED! (MEGA ERROR: " + MegaAPI.checkMEGAError(httpresponse) + ")");

                                } else {

                                    Logger.getLogger(getClass().getName()).log(Level.INFO, "{0} Completion handle -> {1}", new Object[]{Thread.currentThread().getName(), httpresponse});

                                    getUpload().setCompletion_handle(httpresponse);
                                }

                            } else {

                                throw new IOException("UPLOAD FAILED! (Completion handle is empty)");
                            }
                        }

                    } catch (IOException ex) {
                        Logger.getLogger(ChunkUploaderMono.class.getName()).log(Level.SEVERE, null, ex);
                    } catch (CancellationException exception) {

                        throw new IOException("UPLOAD FAILED! (Completion handle is empty)");

                    } finally {

                        if (out != null) {
                            out.flush();
                            out.close();
                        }
                    }

                } else if (error) {

                    if (out != null) {
                        out.flush();
                        out.close();
                    }
                }
            }

        } catch (ChunkInvalidException e) {

        } catch (Exception ex) {

            Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);
        }

        getUpload().stopThisSlot(this);

        getUpload().getMac_generator().secureNotify();

        Logger.getLogger(getClass().getName()).log(Level.INFO, "{0} ChunkUploaderMONO {1} bye bye...", new Object[]{Thread.currentThread().getName(), getId()});
    }

}
