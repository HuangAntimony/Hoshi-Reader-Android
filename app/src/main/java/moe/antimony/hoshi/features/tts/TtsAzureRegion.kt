package moe.antimony.hoshi.features.tts

/** Public-cloud neural TTS regions; IDs also serve as the displayed region names.
 * https://learn.microsoft.com/azure/ai-services/speech-service/regions?tabs=tts
 */
internal enum class TtsAzureRegion(val id: String) {
    AustraliaEast("australiaeast"),
    BrazilSouth("brazilsouth"),
    CanadaCentral("canadacentral"),
    CanadaEast("canadaeast"),
    CentralIndia("centralindia"),
    CentralUs("centralus"),
    EastAsia("eastasia"),
    EastUs("eastus"),
    EastUs2("eastus2"),
    FranceCentral("francecentral"),
    GermanyWestCentral("germanywestcentral"),
    ItalyNorth("italynorth"),
    JapanEast("japaneast"),
    JapanWest("japanwest"),
    KoreaCentral("koreacentral"),
    NorthCentralUs("northcentralus"),
    NorthEurope("northeurope"),
    NorwayEast("norwayeast"),
    QatarCentral("qatarcentral"),
    SouthAfricaNorth("southafricanorth"),
    SouthCentralUs("southcentralus"),
    SoutheastAsia("southeastasia"),
    SwedenCentral("swedencentral"),
    SwitzerlandNorth("switzerlandnorth"),
    SwitzerlandWest("switzerlandwest"),
    UaeNorth("uaenorth"),
    UkSouth("uksouth"),
    UkWest("ukwest"),
    WestCentralUs("westcentralus"),
    WestEurope("westeurope"),
    WestUs("westus"),
    WestUs2("westus2"),
    WestUs3("westus3");

    companion object {
        fun from(id: String): TtsAzureRegion? = entries.firstOrNull { it.id == id }
    }
}
