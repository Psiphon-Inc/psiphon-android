/* PSIPHON: This entire file. */

#include "polipo.h"

AtomPtr splitTunnelingDnsServer = NULL;
NetworkList* localNetworks = NULL;
int splitTunneling = 0; //flag for server.c && tunnel.c
static AtomPtr splitTunnelingFile = NULL;
static AtomPtr psiphonServer = NULL;
struct in_addr psiphonServerAddr;

static int parseSplitFile(AtomPtr filename);
static void destroyNetworkList(void);
static int splitFileObserver(TimeEventHandlerPtr event);
int fileExists(AtomPtr filename);

void
preinitSplitTunneling(void)
{
    CONFIG_VARIABLE(splitTunnelingDnsServer, CONFIG_ATOM_LOWER,
            "The name server to use with split tunneling over SOCKS.");
    CONFIG_VARIABLE(splitTunnelingFile, CONFIG_ATOM,
            "Local networks file for split tunneling");
    CONFIG_VARIABLE(psiphonServer, CONFIG_ATOM_LOWER,
            "Local networks file for split tunneling");
    return;
}

void
initSplitTunneling(void)
{
    int rc;
    if(!splitTunnelingFile || !splitTunnelingFile->string || strlen(splitTunnelingFile->string) == 0)
    {
        return; //Do not do split tunneling if file is not specified
    }

    if(!splitTunnelingDnsServer)
    {
        do_log(L_ERROR, "No splitTunnelingDnsServer provided for split tunneling\n");
        exit(1);
    }
    if(psiphonServer && psiphonServer->string && strlen(psiphonServer->string) > 0)
    {
        memset(&psiphonServerAddr, 0, sizeof(psiphonServerAddr));
        rc = inet_aton(psiphonServer->string, &psiphonServerAddr);
        if(!rc)
        {
            do_log(L_ERROR, "Couldn't parse psiphonServer IP\n");
            exit(1);
        }
    }

    /* schedule splitFileObserver at this point*/
    rc = 0;
    TimeEventHandlerPtr event = scheduleTimeEvent(-1, splitFileObserver, sizeof(rc), &rc);
    if(event == NULL)
    {
        do_log(L_ERROR, "Couldn't schedule splitFileObserver\n");
        exit(1);
    }


}

static int
splitFileObserver(TimeEventHandlerPtr event)
{
    int is_split_on = *(int*)event->data;
    int file_exists = fileExists(splitTunnelingFile);

    if(is_split_on && file_exists)
    {
        splitTunneling = 1;
    }
    else if(is_split_on && !file_exists)
    {
        splitTunneling = 0;
        destroyNetworkList();
    }
    else if(!is_split_on && file_exists)
    {
        int rc = parseSplitFile(splitTunnelingFile);
        if(rc < 0)
        {
            exit(1);
        }
        splitTunneling = 1;
    }
    event = scheduleTimeEvent(5, splitFileObserver, sizeof(splitTunneling), &splitTunneling);
    if(event == NULL)
    {
        do_log(L_ERROR, "Couldn't reschedule splitFileObserver");
        exit(1);
    }
    return 1;
}

static int
parseSplitFile(AtomPtr filename)
{
    char buf[512], *cn, * cm;
    int rc;
    struct in_addr inamask, inanetwork;
    int  mask, i, maskbits;
    FILE *f;

    if(!filename || filename->length == 0)
    {
        do_log(L_ERROR, "Split tunneling file name not supplied\n");
        return -1;

    }
    f = fopen(filename->string, "r");
    if(f == NULL) {
        do_log(L_ERROR, "Couldn't open split tunneling file %s: %d.\n",
                filename->string, errno);
        return -1;
    }

    while(fgets(buf, 512, f)) {
        mask = 0;
        cn = cm = NULL;

        cn = strtok(buf, " \t");
        cm = strtok(NULL, " \t");

        if(!cn || !cm)
            continue;

        rc = inet_aton(cn, &inanetwork);
        if(!rc) //read next line
            continue;

        rc = inet_aton(cm, &inamask);
        if(!rc) //read next line
            continue;

        /* Calculate the number of network bits */
        mask = ntohl(inamask.s_addr);
        for ( maskbits=32 ; (mask & (1L<<(32-maskbits))) == 0 ; maskbits-- )
            ;
        /* Re-create the netmask and compare to the oroginal
         * to make sure it is a valid netmask.
         */
        mask = 0;
        for ( i=0 ; i<maskbits ; i++ )
            mask |= 1<<(31-i);
        if ( mask != ntohl(inamask.s_addr) )
            continue;

        if(localNetworks == NULL) {
            localNetworks = makeNetworkList(0);
            if(localNetworks == NULL) {
                do_log(L_ERROR, "Couldn't allocate NetworkList.\n");
                exit(1);
            }
        }
        NetworkRec nw;
        nw.network = inanetwork;
        nw.netmask = inamask;
        nw.maskbits = maskbits;
        networkListCons(&nw, localNetworks);
    }
    fclose(f);

    if(NULL == localNetworks)
        return 1;

    //Sort the array
    qsort(localNetworks->networks, localNetworks->used, sizeof(NetworkPtr), cmpNetworks);

    //remove duplicate networks from
    //the array
    removeDups(&localNetworks);
    //set flag for DNS over TCP
    splitTunneling = 1;
    return 1;
}

int cmpNetworks(const void *a, const void *b)
{
    unsigned long numa, numb;

    NetworkPtr ia = *(NetworkPtr *) a;
    NetworkPtr ib = *(NetworkPtr *) b;

    numa = ntohl(ia->network.s_addr);
    numb = ntohl(ib->network.s_addr);

    if(numa == numb)
        return 0;
    if(numa > numb)
        return 1;
    return -1;
}

NetworkList*
makeNetworkList(int size)
{
    NetworkList *list;
    if(size <= 0)
        size = 4;

    list = malloc(sizeof(NetworkList));
    if(list == NULL)
        return NULL;

    list->networks = malloc(size * sizeof(NetworkPtr));
    if(list->networks == NULL) {
        free(list);
        return NULL;
    }

    list->used = 0;
    list->size = size;
    return list;
}

void
networkListCons(NetworkPtr network, NetworkList *list)
{
    if(list->networks == NULL) {
        assert(list->used == 0);
    }
    if(list->size <= list->used) {
        NetworkPtr *new_networks;
        int n = (2 * list->used);
        new_networks = realloc(list->networks, n * sizeof(NetworkPtr));
        if(new_networks == NULL) {
            do_log(L_ERROR, "Couldn't realloc NetworkList\n");
            return;
        }
        list->networks = new_networks;
        list->size = n;
    }

    NetworkPtr nw;
    nw = malloc(sizeof(NetworkRec));
    memcpy(nw, network, sizeof(NetworkRec));

    list->networks[list->used] = nw;
    list->used++;
}

void
removeDups(NetworkList** listPtr)
{
    NetworkList* list = *listPtr;
    if(list->networks == NULL)
    {
        assert(list->used == 0);
        return;
    }
    if(list->used == 1)
        return;
    int k = 0;
    int i;
    for (i = 1; i < list->used; i++)
    {
        if (memcmp(&(list->networks[k]->network), &(list->networks[i]->network),
                    sizeof(struct in_addr)))
        {
            k++;
            if(k != i)
            {
                free(list->networks[k]);
                list->networks[k] = list->networks[i];
            }
        }
    }

    // The new array size..
    list->used = k+1;
}

int
isLocalAddress(struct in_addr addr)
{
    if(localNetworks == NULL)
    {
        return 0;
    }
    if(!localNetworks->used)
    {
        return 0;
    }
    //psiphonServer IP should never be split tunneled
    if (0 == memcmp(&addr, &psiphonServerAddr, sizeof(struct in_addr)))
    {
        return 0;
    }
    NetworkPtr *pNetwork = bsearch(&addr, localNetworks->networks, localNetworks->used,
            sizeof(NetworkPtr), addressInNetwork);
    return pNetwork != NULL;
}

int
addressInNetwork( const void * va, const void * vb)
{
    struct in_addr addr = *(struct in_addr *)va;
    NetworkPtr b = *(NetworkPtr *) vb;

    struct in_addr network = b->network;
    struct in_addr netmask = b->netmask;

    if(ntohl(addr.s_addr) <  ntohl(network.s_addr))
        return -1;
    if((network.s_addr & netmask.s_addr) == (addr.s_addr & netmask.s_addr))
        return 0;
    return 1;
}

int
fileExists(AtomPtr filename)
{
    FILE *file = fopen(filename->string, "r");
    if (file)
    {
        fclose(file);
        return 1;
    }
    return 0;
}

static void
destroyNetworkList(void)
{
    int i;
    if(localNetworks) {
        for(i = 0; i < localNetworks->used; i++)
            free(localNetworks->networks[i]);
        localNetworks->used = 0;
        localNetworks->size = 0;
        free(localNetworks->networks);
        free(localNetworks);
        localNetworks = NULL;
    }
}

/* /PSIPHON: entire file */
