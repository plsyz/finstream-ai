package com.finstream.domain;

import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.HashSet;
import java.util.Set;

/**
 * Neo4j node representing a merchant in the financial transaction knowledge graph.
 * Used by the GraphAnomalyAgent to detect multi-hop fraud relationships:
 *   - Merchants sharing IP addresses with flagged entities
 *   - Merchants sharing physical addresses with known fraud rings
 *   - Merchants linked to previously flagged transactions
 */
@Node("Merchant")
public class MerchantNode {

    @Id
    @GeneratedValue
    private Long id;

    private String merchantId;
    private String name;
    private String category;
    private String country;
    private String registeredAddress;
    private String ipAddress;
    private boolean flagged;
    private String flagReason;

    @Relationship(type = "SHARES_IP_WITH", direction = Relationship.Direction.OUTGOING)
    private Set<MerchantNode> sharesIpWith = new HashSet<>();

    @Relationship(type = "SHARES_ADDRESS_WITH", direction = Relationship.Direction.OUTGOING)
    private Set<MerchantNode> sharesAddressWith = new HashSet<>();

    @Relationship(type = "PREVIOUSLY_FLAGGED_IN", direction = Relationship.Direction.OUTGOING)
    private Set<FraudCaseNode> previousFraudCases = new HashSet<>();

    public MerchantNode() {}

    public MerchantNode(String merchantId, String name, String category, String country) {
        this.merchantId = merchantId;
        this.name = name;
        this.category = category;
        this.country = country;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    public String getRegisteredAddress() { return registeredAddress; }
    public void setRegisteredAddress(String registeredAddress) { this.registeredAddress = registeredAddress; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public boolean isFlagged() { return flagged; }
    public void setFlagged(boolean flagged) { this.flagged = flagged; }

    public String getFlagReason() { return flagReason; }
    public void setFlagReason(String flagReason) { this.flagReason = flagReason; }

    public Set<MerchantNode> getSharesIpWith() { return sharesIpWith; }
    public void setSharesIpWith(Set<MerchantNode> sharesIpWith) { this.sharesIpWith = sharesIpWith; }

    public Set<MerchantNode> getSharesAddressWith() { return sharesAddressWith; }
    public void setSharesAddressWith(Set<MerchantNode> sharesAddressWith) { this.sharesAddressWith = sharesAddressWith; }

    public Set<FraudCaseNode> getPreviousFraudCases() { return previousFraudCases; }
    public void setPreviousFraudCases(Set<FraudCaseNode> previousFraudCases) { this.previousFraudCases = previousFraudCases; }
}
