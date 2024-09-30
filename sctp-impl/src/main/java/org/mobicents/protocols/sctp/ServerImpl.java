/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011, Red Hat, Inc. and/or its affiliates, and individual
 * contributors as indicated by the @authors tag. All rights reserved.
 * See the copyright.txt in the distribution for a full listing
 * of individual contributors.
 *
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU General Public License, v. 2.0.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License,
 * v. 2.0 along with this distribution; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA 02110-1301, USA.
 */

package org.mobicents.protocols.sctp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.util.List;

import com.sun.nio.sctp.SctpSocketOption;
import com.sun.nio.sctp.SctpStandardSocketOptions;
import javolution.util.FastList;
import javolution.xml.XMLFormat;
import javolution.xml.stream.XMLStreamException;

import org.apache.log4j.Logger;
import org.mobicents.protocols.api.Association;
import org.mobicents.protocols.api.IpChannelType;
import org.mobicents.protocols.api.Server;

import com.sun.nio.sctp.SctpServerChannel;

/**
 * @author amit bhayani
 * @author sergey vetyutnev
 *
 */
public class ServerImpl implements Server {

    private static final Logger logger = Logger.getLogger(ServerImpl.class.getName());

    private static final String COMMA = ", ";
    private static final String NAME = "name";
    private static final String HOST_ADDRESS = "hostAddress";
    private static final String HOST_PORT = "hostPort";
    private static final String IP_CHANNEL_TYPE = "ipChannelType";

    private static final String ASSOCIATIONS = "associations";
    private static final String EXTRA_HOST_ADDRESS = "extraHostAddress";
	private static final String ACCEPT_ANONYMOUS_CONNECTIONS = "acceptAnonymousConnections";
	private static final String MAX_CONCURRENT_CONNECTIONS_COUNT = "maxConcurrentConnectionsCount";

	private static final String MAX_INPUT_STREAMS = "maxInputStreams";
	private static final String MAX_OUTPUT_STREAMS = "maxOutputStreams";

	private static final String STARTED = "started";

    private static final String EXTRA_HOST_ADDRESSES_SIZE = "extraHostAddressesSize";

    private String name;
    private String hostAddress;
    private int hostPort;
    private volatile boolean started = false;
    private IpChannelType ipChannelType;
    private boolean acceptAnonymousConnections;
    private int maxConcurrentConnectionsCount;
    private String[] extraHostAddresses;

    private int maxInputStreams; //32 - 019
	private int maxOutputStreams; //32 - 019

	private ManagementImpl management = null;

    protected FastList<String> associations = new FastList<String>();
    protected FastList<Association> anonymAssociations = new FastList<Association>();

    // The channel on which we'll accept connections
    private SctpServerChannel serverChannelSctp;
    private ServerSocketChannel serverChannelTcp;

	/**
	 *
	 */
	public ServerImpl() {
		super();
	}

	/**
	 * @param serverName
     * @param hostAddress
     * @param hostPort
     * @param ipChannelType
     * @param acceptAnonymousConnections
     * @param maxConcurrentConnectionsCount
     * @param maxInputSctpStreams
     * @param maxOutputSctpStreams
     * @param extraHostAddresses
     * @throws IOException
	 */
	public ServerImpl(String serverName, String hostAddress, int hostPort, IpChannelType ipChannelType, boolean acceptAnonymousConnections,
              int maxConcurrentConnectionsCount, int maxInputSctpStreams, int maxOutputSctpStreams, String[] extraHostAddresses) throws IOException {
		super();
		this.name = serverName;
		this.hostAddress = hostAddress;
		this.hostPort = hostPort;
		this.ipChannelType = ipChannelType;
		this.acceptAnonymousConnections = acceptAnonymousConnections;
		this.maxConcurrentConnectionsCount = maxConcurrentConnectionsCount;
		this.maxInputStreams = maxInputSctpStreams;
		this.maxOutputStreams = maxOutputSctpStreams;
		this.extraHostAddresses = extraHostAddresses;
	}

    protected void start() throws Exception {
        this.initSocket();
        this.started = true;

        if (logger.isInfoEnabled()) {
            logger.info(String.format("Started Server=%s", this.name));
        }
    }

    protected void stop() throws Exception {
        FastList<String> tempAssociations = associations;
        for (FastList.Node<String> n = tempAssociations.head(), end = tempAssociations.tail(); (n = n.getNext()) != end;) {
            String assocName = n.getValue();
            Association associationTemp = this.management.getAssociation(assocName);
            if (associationTemp.isStarted()) {
                throw new Exception(String.format("Stop all the associations first. Association=%s is still started",
                    associationTemp.getName()));
            }
        }

        synchronized (this.anonymAssociations) {
            // stopping all anonymous associations
            for (Association ass : this.anonymAssociations) {
                ass.stopAnonymousAssociation();
            }
            this.anonymAssociations.clear();
        }

        if (this.getIpChannel() != null) {
            try {
                this.getIpChannel().close();
            } catch (Exception e) {
                logger.warn(String.format("Error while stopping the Server=%s", this.name), e);
            }
        }

        this.started = false;

        if (logger.isInfoEnabled()) {
            logger.info(String.format("Stopped Server=%s", this.name));
        }
    }

    private void initSocket() throws IOException {

        if (this.ipChannelType == IpChannelType.SCTP)
            doInitSocketSctp();
        else
            doInitSocketTcp();

        // Register the server socket channel, indicating an interest in
        // accepting new connections
        // this.serverChannel.register(socketSelector, SelectionKey.OP_ACCEPT);

        FastList<ChangeRequest> pendingChanges = this.management.getPendingChanges();
        synchronized (pendingChanges) {

            // Indicate we want the interest ops set changed
            pendingChanges.add(new ChangeRequest(this.getIpChannel(), null, ChangeRequest.REGISTER,
                SelectionKey.OP_ACCEPT));
        }

        this.management.getSocketSelector().wakeup();
    }

    private void doInitSocketSctp() throws IOException {
        // Create a new non-blocking server socket channel
        this.serverChannelSctp = SctpServerChannel.open();
        this.serverChannelSctp.configureBlocking(false);

        // Set maximum streams
        SctpStandardSocketOptions.InitMaxStreams initMaxStreams =
                SctpStandardSocketOptions.InitMaxStreams.create(this.maxInputStreams, this.maxOutputStreams);
        serverChannelSctp.setOption(SctpStandardSocketOptions.SCTP_INIT_MAXSTREAMS, initMaxStreams);

		// Bind the server socket to the specified address and port
		InetSocketAddress isa = new InetSocketAddress(this.hostAddress, this.hostPort);
		this.serverChannelSctp.bind(isa);
		if (this.extraHostAddresses != null) {
			for (String s : extraHostAddresses) {
				this.serverChannelSctp.bindAddress(InetAddress.getByName(s));
			}
		}

		if (logger.isInfoEnabled()) {
			logger.info(String.format("SctpServerChannel bound to=%s", serverChannelSctp.getAllLocalAddresses()));
		}
	}

    private void doInitSocketTcp() throws IOException {
        // Create a new non-blocking server socket channel
        this.serverChannelTcp = ServerSocketChannel.open();
        this.serverChannelTcp.configureBlocking(false);

        // Bind the server socket to the specified address and port
        InetSocketAddress isa = new InetSocketAddress(this.hostAddress, this.hostPort);
        this.serverChannelTcp.bind(isa);

        if (logger.isInfoEnabled()) {
            logger.info(String.format("ServerSocketChannel bound to=%s ", serverChannelTcp.getLocalAddress()));
        }
    }

    public IpChannelType getIpChannelType() {
        return this.ipChannelType;
    }

    public void setIpChannelType(IpChannelType ipChannelType) {
        this.ipChannelType = ipChannelType;
    }

    public boolean isAcceptAnonymousConnections() {
        return acceptAnonymousConnections;
    }

    public void setAcceptAnonymousConnections(boolean acceptAnonymousConnections) {
        this.acceptAnonymousConnections = acceptAnonymousConnections;
    }

    public int getMaxConcurrentConnectionsCount() {
        return maxConcurrentConnectionsCount;
    }

    public void setMaxConcurrentConnectionsCount(int val) {
        maxConcurrentConnectionsCount = val;
    }

    public List<Association> getAnonymAssociations() {
        return this.anonymAssociations.unmodifiable();
    }

    public int getMaxInputStreams() {
		return maxInputStreams;
	}

	public void setMaxInputStreams(int maxInputStreams) {
		this.maxInputStreams = maxInputStreams;
	}

	public int getMaxOutputStreams() {
		return maxOutputStreams;
	}

	public void setMaxOutputStreams(int maxOutputStreams) {
		this.maxOutputStreams = maxOutputStreams;
	}protected AbstractSelectableChannel getIpChannel() {
        if (this.ipChannelType == IpChannelType.SCTP)
            return this.serverChannelSctp;
        else
            return this.serverChannelTcp;
    }

    /**
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * @return the hostAddress
     */
    public String getHostAddress() {
        return hostAddress;
    }

    public void setHostAddress(String hostAddress) {
        this.hostAddress = hostAddress;
    }

    /**
     * @return the hostport
     */
    public int getHostport() {
        return hostPort;
    }

    public void setHostport(int hostport) {
        this.hostPort = hostport;
    }

    @Override
    public String[] getExtraHostAddresses() {
        return extraHostAddresses;
    }

    public void setExtraHostAddresses(String[] extraHostAddresses) {
        this.extraHostAddresses = extraHostAddresses;
    }

    /**
     * @return the started
     */
    public boolean isStarted() {
        return started;
    }

	/**
	 * @param management the management to set
	 */
	public void setManagement(ManagementImpl management) {
		this.management = management;
	}

    /**
     * @return the associations
     */
    public List<String> getAssociations() {
        return associations.unmodifiable();
    }

    @Override
    public String toString() {

        StringBuilder sb = new StringBuilder();

		sb.append("Server [name=").append(this.name).append(", started=").append(this.started).append(", hostAddress=").append(this.hostAddress)
			.append(", hostPort=").append(hostPort).append(", ipChannelType=").append(ipChannelType).append(", acceptAnonymousConnections=")
			.append(this.acceptAnonymousConnections).append(", maxConcurrentConnectionsCount=").append(this.maxConcurrentConnectionsCount)
			.append(", maxInputStreams=").append(maxInputStreams).append(", maxOutputStreams=").append(maxOutputStreams)
			.append(", associations(anonymous does not included)=[");

		for (FastList.Node<String> n = this.associations.head(), end = this.associations.tail(); (n = n.getNext()) != end; ) {
			sb.append(n.getValue());
			sb.append(", ");
		}

        sb.append("], extraHostAddress=[");

        if (this.extraHostAddresses != null) {
            for (int i = 0; i < this.extraHostAddresses.length; i++) {
                String extraHostAddress = this.extraHostAddresses[i];
                sb.append(extraHostAddress);
                sb.append(", ");
            }
        }

        sb.append("]]");

        return sb.toString();
    }

    /**
     * XML Serialization/Deserialization
     */
    protected static final XMLFormat<ServerImpl> SERVER_XML = new XMLFormat<ServerImpl>(ServerImpl.class) {

        @SuppressWarnings("unchecked")
        @Override
        public void read(javolution.xml.XMLFormat.InputElement xml, ServerImpl server) throws XMLStreamException {
            server.name = xml.getAttribute(NAME, "");
            server.started = xml.getAttribute(STARTED, false);
            server.hostAddress = xml.getAttribute(HOST_ADDRESS, "");
            server.hostPort = xml.getAttribute(HOST_PORT, 0);
            server.ipChannelType = IpChannelType.getInstance(xml.getAttribute(IP_CHANNEL_TYPE,
                IpChannelType.SCTP.getCode()));
            if (server.ipChannelType == null)
                throw new XMLStreamException("Bad value for server.ipChannelType");

            server.acceptAnonymousConnections = xml.getAttribute(ACCEPT_ANONYMOUS_CONNECTIONS, false);
			server.maxConcurrentConnectionsCount = xml.getAttribute(MAX_CONCURRENT_CONNECTIONS_COUNT, 0);

            server.maxInputStreams = xml.getAttribute(MAX_INPUT_STREAMS, 32);
            server.maxOutputStreams = xml.getAttribute(MAX_OUTPUT_STREAMS, 32);

            int extraHostAddressesSize = xml.getAttribute(EXTRA_HOST_ADDRESSES_SIZE, 0);
            server.extraHostAddresses = new String[extraHostAddressesSize];

            for(int i=0;i<extraHostAddressesSize;i++){
                server.extraHostAddresses[i] = xml.get(EXTRA_HOST_ADDRESS, String.class);
            }

            server.associations = xml.get(ASSOCIATIONS, FastList.class);
        }

        @Override
        public void write(ServerImpl server, javolution.xml.XMLFormat.OutputElement xml) throws XMLStreamException {
            xml.setAttribute(NAME, server.name);
            xml.setAttribute(STARTED, server.started);
            xml.setAttribute(HOST_ADDRESS, server.hostAddress);
            xml.setAttribute(HOST_PORT, server.hostPort);
            xml.setAttribute(IP_CHANNEL_TYPE, server.ipChannelType.getCode());
			xml.setAttribute(ACCEPT_ANONYMOUS_CONNECTIONS, server.acceptAnonymousConnections);
			xml.setAttribute(MAX_CONCURRENT_CONNECTIONS_COUNT, server.maxConcurrentConnectionsCount);

			xml.setAttribute(MAX_INPUT_STREAMS, server.maxInputStreams);
			xml.setAttribute(MAX_OUTPUT_STREAMS, server.maxOutputStreams);

			xml.setAttribute(EXTRA_HOST_ADDRESSES_SIZE,
				server.extraHostAddresses != null ? server.extraHostAddresses.length : 0);
			if (server.extraHostAddresses != null) {
				for (String s : server.extraHostAddresses) {
					xml.add(s, EXTRA_HOST_ADDRESS, String.class);
				}
			}
			xml.add(server.associations, ASSOCIATIONS, FastList.class);
		}
	};
}
