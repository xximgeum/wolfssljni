/* WolfSSLTrustX509.java
 *
 * Copyright (C) 2006-2022 wolfSSL Inc.
 *
 * This file is part of wolfSSL.
 *
 * wolfSSL is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * wolfSSL is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA
 */

package com.wolfssl.provider.jsse;

import com.wolfssl.WolfSSL;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;

import com.wolfssl.WolfSSLCertManager;
import com.wolfssl.WolfSSLException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.util.HashSet;
import java.util.Set;

/**
 * wolfSSL implementation of X509TrustManager
 *
 * @author wolfSSL
 */
public class WolfSSLTrustX509 implements X509TrustManager {
    private KeyStore store = null;

    /**
     * Create new WolfSSLTrustX509 object
     *
     * @param in KeyStore to use with this X509TrustManager
     */
    public WolfSSLTrustX509(KeyStore in) {
        this.store = in;

        WolfSSLDebug.log(getClass(), WolfSSLDebug.INFO,
            "created new WolfSSLTrustX509");
    }

    /**
     * Verify cert chain using WolfSSLCertManager, throw CertificateException
     * on error/failure. Do all loading and verification in one function to
     * avoid holding native resources at the object/class level. */
    private void certManagerVerify(X509Certificate[] certs, String type)
        throws CertificateException {

        int ret = WolfSSL.SSL_FAILURE;
        WolfSSLCertManager cm = null;

        if (certs == null || certs.length == 0 || type.length() == 0) {
            throw new CertificateException();
        }

        /* create new WolfSSLCertManager */
        try {
            cm = new WolfSSLCertManager();
        } catch (WolfSSLException e) {
            throw new CertificateException(
                "Failed to create native WolfSSLCertManager");
        }

        /* load trusted certs from KeyStore */
        try {
            ret = cm.CertManagerLoadCAKeyStore(this.store);
        } catch (WolfSSLException e) {
            cm.free();
            throw new CertificateException(
                "Failed to load trusted certs into WolfSSLCertManager");
        }

        /* Here we assume certs chain starts with peer certificate (certs[0])
         * and is followed incrementally by intermedaite certificates in the
         * correct order. If the chain is out of order, this verification
         * will fail and reorder logic will need to be implemented
         *
         * Walk backwards down list of intermediate CA certs, verify each one
         * based on trusted certs we already have loaeded in the CertManager,
         * then once verified load the intermediate into the CertManager
         * as a root that can be used to verify our peer cert. */

        for (int i = certs.length-1; i > 0; i--) {

            /* Verify chain cert */
            WolfSSLDebug.log(getClass(), WolfSSLDebug.INFO,
                "Verifying intermediate chain cert: " +
                certs[i].getSubjectX500Principal().getName());

            byte[] encoded = certs[i].getEncoded();
            ret = cm.CertManagerVerifyBuffer(encoded, encoded.length,
                    WolfSSL.SSL_FILETYPE_ASN1);
            if (ret != WolfSSL.SSL_SUCCESS) {
                cm.free();
                throw new CertificateException("Failed to verify " +
                    "intermediate chain cert");
            }

            /* Load chain cert as trusted CA */
            ret = cm.CertManagerLoadCABuffer(encoded, encoded.length,
                    WolfSSL.SSL_FILETYPE_ASN1);
            if (ret != WolfSSL.SSL_SUCCESS) {
                cm.free();
                throw new CertificateException("Failed to load intermediate " +
                    "CA certificate as trusted root");
            }

            WolfSSLDebug.log(getClass(), WolfSSLDebug.INFO,
                "Loaded intermediate CA: " +
                certs[i].getSubjectX500Principal().getName());
        }

        /* Verify peer certificate */
        WolfSSLDebug.log(getClass(), WolfSSLDebug.INFO,
            "Verifying peer certificate: " +
            certs[0].getSubjectX500Principal().getName());

        byte[] peer = certs[0].getEncoded();
        if (peer == null) {
            cm.free();
            throw new CertificateException("Failed to get encoded peer cert");
        }

        ret = cm.CertManagerVerifyBuffer(peer, peer.length,
                WolfSSL.SSL_FILETYPE_ASN1);
        if (ret != WolfSSL.SSL_SUCCESS) {
            WolfSSLDebug.log(getClass(), WolfSSLDebug.INFO,
                "Failed to verify peer certificate");
            cm.free();
            throw new CertificateException("Failed to verify peer certificate");
        }

        WolfSSLDebug.log(getClass(), WolfSSLDebug.INFO,
            "Verified peer certificate: " +
            certs[0].getSubjectX500Principal().getName());

        cm.free();
    }

    @Override
    public void checkClientTrusted(X509Certificate[] certs, String type)
            throws CertificateException {

        WolfSSLDebug.log(getClass(), WolfSSLDebug.INFO,
            "entered checkClientTrusted()");

        certManagerVerify(certs, type);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] certs, String type)
        throws CertificateException {

        WolfSSLDebug.log(getClass(), WolfSSLDebug.INFO,
            "entered checkServerTrusted()");

        certManagerVerify(certs, type);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {

        WolfSSLDebug.log(getClass(), WolfSSLDebug.INFO,
            "entered getAcceptedIssuers()");

        try {
            List<X509Certificate> CAs = new ArrayList<X509Certificate>();
            /* Store the alias of all CAs */
            Enumeration<String> aliases = store.aliases();
            while (aliases.hasMoreElements()) {
                final String name = aliases.nextElement();
                X509Certificate cert = null;

                if (store.isKeyEntry(name)) {
                    Certificate[] chain = store.getCertificateChain(name);
                    if (chain != null)
                        cert = (X509Certificate) chain[0];
                } else {
                    cert = (X509Certificate) store.getCertificate(name);
                }

                if (cert != null && cert.getBasicConstraints() >= 0) {
                    CAs.add(cert);
                }
            }

            return CAs.toArray(new X509Certificate[CAs.size()]);

        } catch (KeyStoreException ex) {
            return new X509Certificate[0];
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void finalize() throws Throwable {
        this.store = null;
        super.finalize();
    }
}

