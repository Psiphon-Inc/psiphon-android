/* Shared library add-on to ip6tables to add EUI64 address checking support. */
#include <xtables.h>

static struct xtables_match eui64_mt6_reg = {
	.name 		= "eui64",
	.version	= XTABLES_VERSION,
	.family		= NFPROTO_IPV6,
	.size		= XT_ALIGN(sizeof(int)),
	.userspacesize	= XT_ALIGN(sizeof(int)),
};

void libip6t_eui64_init(void)
{
	xtables_register_match(&eui64_mt6_reg);
}
