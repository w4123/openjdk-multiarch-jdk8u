/*
 * Copyright 2019-2020 Azul Systems,
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 *    may be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.azul.crs.client.util;

import sun.net.dns.ResolverConfiguration;

import javax.naming.NamingException;
import java.io.IOException;
import java.util.List;
import javax.naming.Context;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;

public final class DnsDetect {

    private final List<String> searchlist;
    private final String postfix;

    public DnsDetect(String stackUuid) throws IOException {
        postfix = stackUuid == null ? "" : "_" + stackUuid;
        ResolverConfiguration rc = ResolverConfiguration.open();
        searchlist = rc.searchlist();
        searchlist.add(0, "");
    }

    private String query(String name, String type) {
        try {
            InitialDirContext context = new InitialDirContext();
            try {
                context.addToEnvironment(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
                for (String domain : searchlist) {
                    String qname = (domain.isEmpty()) ? name : name + "." + domain;
                    try {
                        Attributes attributes = context.getAttributes(qname, new String[]{type});
                        Attribute attribute = attributes.get(type);
                        if (attribute != null) {
                            Object result = attribute.get();
                            if (result != null) {
                                return result.toString();
                            }
                        }
                    } catch (NamingException ex) {
                    }
                }
            } catch (NamingException ex) {
            } finally {
                context.close();
            }
        } catch (NamingException ex) {
        }
        return null;
    }

    public String queryEndpoint() throws IOException {
        String result = query("crs-endpoint" + postfix, "CNAME");
        return result == null ? null : result.endsWith(".") ? result.substring(0, result.length() - 1) : result;
    }

    public String queryMailbox() throws IOException {
        return query("crs-mailbox" + postfix, "TXT");
    }

    public String getRecordNamePostfix() {
        return postfix;
    }
}
