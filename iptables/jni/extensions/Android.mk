LOCAL_PATH:= $(call my-dir)
#----------------------------------------------------------------
## extension

MY_srcdir:=$(LOCAL_PATH)
# Exclude some modules that are problematic to compile (types/header).
MY_excluded_modules:=TCPOPTSTRIP

MY_pfx_build_mod := $(patsubst ${MY_srcdir}/libxt_%.c,%,$(wildcard ${MY_srcdir}/libxt_*.c))
MY_pf4_build_mod := $(patsubst ${MY_srcdir}/libipt_%.c,%,$(wildcard ${MY_srcdir}/libipt_*.c))
MY_pf6_build_mod := $(patsubst ${MY_srcdir}/libip6t_%.c,%,$(wildcard ${MY_srcdir}/libip6t_*.c))
MY_pfx_build_mod := $(filter-out ${MY_excluded_modules} dccp ipvs,${MY_pfx_build_mod})
MY_pf4_build_mod := $(filter-out ${MY_excluded_modules} dccp ipvs,${MY_pf4_build_mod})
MY_pf6_build_mod := $(filter-out ${MY_excluded_modules} dccp ipvs,${MY_pf6_build_mod})
MY_pfx_objs      := $(patsubst %,libxt_%.o,${MY_pfx_build_mod})
MY_pf4_objs      := $(patsubst %,libipt_%.o,${MY_pf4_build_mod})
MY_pf6_objs      := $(patsubst %,libip6t_%.o,${MY_pf6_build_mod})

#----------------------------------------------------------------
# libext
# TODO(jpa): Trun this into a function/macro as libext{,4,6} are all the same.

include $(CLEAR_VARS)

LOCAL_MODULE_TAGS:=
LOCAL_MODULE:=libext

# LOCAL_MODULE_CLASS must be defined before calling $(local-intermediates-dir)
#
LOCAL_MODULE_CLASS := STATIC_LIBRARIES
MY_intermediates := $(call local-intermediates-dir)

# LOCAL_PATH needed because of dirty #include "blabla.c"
LOCAL_C_INCLUDES:= \
	$(LOCAL_PATH)/../include/ \
	$(KERNEL_HEADERS) \
	$(MY_intermediates) \
	$(LOCAL_PATH)

LOCAL_CFLAGS:=-DNO_SHARED_LIBS=1
# The $* does not work as expected. It ends up empty. Even with SECONDEXPANSION.
# LOCAL_CFLAGS+=-D_INIT=lib$*_init
LOCAL_CFLAGS+=-DXTABLES_INTERNAL
# Accommodate arm-eabi-4.4.3 tools that don't set __ANDROID__
LOCAL_CFLAGS+=-D__ANDROID__

MY_initext_func := $(addprefix xt_,${MY_pfx_build_mod})
MY_GEN_INITEXT:= $(MY_intermediates)/initext.c
$(MY_GEN_INITEXT):
	@mkdir -p $(dir $@)
	@( \
	echo "" >$@; \
	for i in ${MY_initext_func}; do \
		echo "extern void lib$${i}_init(void);" >>$@; \
	done; \
	echo "void init_extensions(void);" >>$@; \
	echo "void init_extensions(void)" >>$@; \
	echo "{" >>$@; \
	for i in ${MY_initext_func}; do \
		echo " ""lib$${i}_init();" >>$@; \
	done; \
	echo "}" >>$@; \
	);

MY_lib_sources:= \
	$(patsubst %,$(LOCAL_PATH)/libxt_%.c,${MY_pfx_build_mod})

MY_gen_lib_sources:= $(patsubst $(LOCAL_PATH)/%,${MY_intermediates}/%,${MY_lib_sources})

${MY_gen_lib_sources}: PRIVATE_PATH := $(LOCAL_PATH)
${MY_gen_lib_sources}: PRIVATE_CUSTOM_TOOL = $(PRIVATE_PATH)/filter_init $(PRIVATE_PATH)/$(notdir $@) > $@
${MY_gen_lib_sources}: PRIVATE_MODULE := $(LOCAL_MODULE)
${MY_gen_lib_sources}: PRIVATE_C_INCLUDES := $(LOCAL_C_INCLUDES)
${MY_gen_lib_sources}: $(MY_lib_sources)
	$(transform-generated-source)

$(MY_intermediates)/initext.o : $(MY_GEN_INITEXT) $(MY_gen_lib_sources)

#LOCAL_GENERATED_SOURCES:= $(MY_GEN_INITEXT) $(MY_gen_lib_sources)
LOCAL_SRC_FILES:= $(MY_GEN_INITEXT) $(MY_gen_lib_sources)

include $(BUILD_STATIC_LIBRARY)

#----------------------------------------------------------------
# libext4

include $(CLEAR_VARS)

LOCAL_MODULE_TAGS:=
LOCAL_MODULE:=libext4

# LOCAL_MODULE_CLASS must be defined before calling $(local-intermediates-dir)
#
LOCAL_MODULE_CLASS := STATIC_LIBRARIES
MY_intermediates := $(call local-intermediates-dir)

# LOCAL_PATH needed because of dirty #include "blabla.c"
LOCAL_C_INCLUDES:= \
	$(LOCAL_PATH)/../include/ \
	$(KERNEL_HEADERS) \
	$(MY_intermediates)/ \
	$(LOCAL_PATH)/

LOCAL_CFLAGS:=-DNO_SHARED_LIBS=1
# The $* does not work as expected. It ends up empty. Even with SECONDEXPANSION.
# LOCAL_CFLAGS+=-D_INIT=lib$*_init
LOCAL_CFLAGS+=-DXTABLES_INTERNAL
# Accommodate arm-eabi-4.4.3 tools that don't set __ANDROID__
LOCAL_CFLAGS+=-D__ANDROID__

MY_initext4_func  := $(addprefix ipt_,${MY_pf4_build_mod})
MY_GEN_INITEXT4:= $(MY_intermediates)/initext4.c
$(MY_GEN_INITEXT4):
	@mkdir -p $(dir $@)
	@( \
	echo "" >$@; \
	for i in ${MY_initext4_func}; do \
		echo "extern void lib$${i}_init(void);" >>$@; \
	done; \
	echo "void init_extensions4(void);" >>$@; \
	echo "void init_extensions4(void)" >>$@; \
	echo "{" >>$@; \
	for i in ${MY_initext4_func}; do \
		echo  " ""lib$${i}_init();" >>$@; \
	done; \
	echo "}" >>$@; \
	);

MY_lib_sources:= \
	$(patsubst %,$(LOCAL_PATH)/libipt_%.c,${MY_pf4_build_mod})

MY_gen_lib_sources:= $(patsubst $(LOCAL_PATH)/%,${MY_intermediates}/%,${MY_lib_sources})

${MY_gen_lib_sources}: PRIVATE_PATH := $(LOCAL_PATH)
${MY_gen_lib_sources}: PRIVATE_CUSTOM_TOOL = $(PRIVATE_PATH)/filter_init $(PRIVATE_PATH)/$(notdir $@) > $@
${MY_gen_lib_sources}: PRIVATE_MODULE := $(LOCAL_MODULE)
${MY_gen_lib_sources}: PRIVATE_C_INCLUDES := $(LOCAL_C_INCLUDES)
${MY_gen_lib_sources}: $(MY_lib_sources)
	$(transform-generated-source)

$(MY_intermediates)/initext4.o : $(MY_GEN_INITEXT4) $(MY_gen_lib_sources)

#LOCAL_GENERATED_SOURCES:= $(MY_GEN_INITEXT4) ${MY_gen_lib_sources}
LOCAL_SRC_FILES:= $(MY_GEN_INITEXT4) ${MY_gen_lib_sources}

include $(BUILD_STATIC_LIBRARY)

#----------------------------------------------------------------
# libext6

include $(CLEAR_VARS)

LOCAL_MODULE_TAGS:=
LOCAL_MODULE:=libext6

# LOCAL_MODULE_CLASS must be defined before calling $(local-intermediates-dir)
#
LOCAL_MODULE_CLASS := STATIC_LIBRARIES
MY_intermediates := $(call local-intermediates-dir)

# LOCAL_PATH needed because of dirty #include "blabla.c"
LOCAL_C_INCLUDES:= \
	$(LOCAL_PATH)/../include/ \
	$(KERNEL_HEADERS) \
	$(MY_intermediates) \
	$(LOCAL_PATH)

LOCAL_CFLAGS:=-DNO_SHARED_LIBS=1
# The $* does not work as expected. It ends up empty. Even with SECONDEXPANSION.
# LOCAL_CFLAGS+=-D_INIT=lib$*_init
LOCAL_CFLAGS+=-DXTABLES_INTERNAL
# Accommodate arm-eabi-4.4.3 tools that don't set __ANDROID__
LOCAL_CFLAGS+=-D__ANDROID__

MY_initext6_func := $(addprefix ip6t_,${MY_pf6_build_mod})
MY_GEN_INITEXT6:= $(MY_intermediates)/initext6.c
$(MY_GEN_INITEXT6):
	@mkdir -p $(dir $@)
	@( \
	echo "" >$@; \
	for i in ${MY_initext6_func}; do \
		echo "extern void lib$${i}_init(void);" >>$@; \
	done; \
	echo "void init_extensions6(void);" >>$@; \
	echo "void init_extensions6(void)" >>$@; \
	echo "{" >>$@; \
	for i in ${MY_initext6_func}; do \
		echo " ""lib$${i}_init();" >>$@; \
	done; \
	echo "}" >>$@; \
	);

MY_lib_sources:= \
	$(patsubst %,$(LOCAL_PATH)/libip6t_%.c,${MY_pf6_build_mod})

MY_gen_lib_sources:= $(patsubst $(LOCAL_PATH)/%,${MY_intermediates}/%,${MY_lib_sources})

${MY_gen_lib_sources}: PRIVATE_PATH := $(LOCAL_PATH)
${MY_gen_lib_sources}: PRIVATE_CUSTOM_TOOL = $(PRIVATE_PATH)/filter_init $(PRIVATE_PATH)/$(notdir $@) > $@
${MY_gen_lib_sources}: PRIVATE_MODULE := $(LOCAL_MODULE)
${MY_gen_lib_sources}: PRIVATE_C_INCLUDES := $(LOCAL_C_INCLUDES)
${MY_gen_lib_sources}: $(MY_lib_sources)
	$(transform-generated-source)

$(MY_intermediates)/initext6.o : $(MY_GEN_INITEXT6) $(MY_gen_lib_sources)

#LOCAL_GENERATED_SOURCES:= $(MY_GEN_INITEXT6) $(MY_gen_lib_sources)
LOCAL_SRC_FILES:= $(MY_GEN_INITEXT6) $(MY_gen_lib_sources)

include $(BUILD_STATIC_LIBRARY)

#----------------------------------------------------------------
