import command
import json
import fmtcnv
"""
POLICY_SUBMODE_COMMAND_DESCRIPTION = {
    'name'          : 'policy',
    'short-help'    : 'Enter policy submode, configure SR policy details',
    'mode'          : 'config',
    'parent-field'  : None,
    'command-type'  : 'config-submode',
    'obj-type'      : 'policy-config',
    'submode-name'  : 'config-policy',
    'doc'           : 'policy|policy',
    'doc-example'   : 'policy|tunnel-example',
    'args' : (
        {
            'field'        : 'policy-id',
            'type'         : 'identifier',
            'completion'   : 'complete-object-field',
            'syntax-help'  : 'Enter a policy id',
            'doc'          : 'policy|policy',
            'doc-include'  : [ 'type-doc' ],
            'action'       : (
                                {
                                    'proc' : 'create-policy',
                                },
                                {
                                    'proc' : 'push-mode-stack',
                                },
                              ),
            'no-action': (
                {
                    'proc' : 'remove-policy',
                }
            ),
        }
    )
}
"""
POLICY_CONFIG_FORMAT = {
    'policy-config' : {
        'field-orderings' : {
            'default' : [
                          'policy-id',
                        ],
        },
    },
}

POLICY_SUBMODE_COMMAND_DESCRIPTION = {
    'name'          : 'policy',
    'short-help'    : 'Enter policy submode, configure SR policy details',
    'mode'          : 'config',
    'command-type'  : 'config-submode',
    'obj-type'      : 'policy-config',
    'submode-name'  : 'config-policy',
    'parent-field'  : None,
    'doc'           : 'policy|policy',
    'doc-example'   : 'policy|policy-example',
    'args' : {
        'action'       : (
                            {
                                'proc' : 'create-policy',
                            },
                            {
                                'proc' : 'push-mode-stack',
                            },
                         ),
        'no-action'    : (
                            {
                                'proc' : 'remove-policy',
                            },
                         ),
        'choices'      : (
            (
                {
                    'field'        : 'policy-id',
                    'type'         : 'identifier',
                    'completion'   : 'complete-object-field',
                    'syntax-help'  : 'Enter a policy name',
                    'doc'          : 'policy|policy',
                    #'doc-include'  : [ 'type-doc' ],
                },
                {
                    'token'        : 'type',
                    'short-help'   : 'Set type of policy',
                    'doc'          : 'policy|policy',
                    'completion'   : 'complete-object-field',
                },
                {
                    'field'        : 'policy-type',
                    'type'         : 'enum',
                    'values'       : ('tunnel','loadbalanced','avoid','deny'), 
                    'completion'   : 'complete-object-field',
                    'syntax-help'  : 'Enter a policy type',
                    'doc'          : 'policy|policy',
                    #'doc-include'  : [ 'type-doc' ],
                },
            ),
        ),
    }
}

SRC_IP_MATCH = {
    'choices' : (
        (
            {
                'field'        : 'src_ip',
                'type'         : 'cidr-range',
                'help-name'    : 'src-cidr',
                #'data-handler' : 'split-cidr-data-inverse',
                #'dest-ip'      : 'src-ip',
                #'dest-netmask' : 'src-ip-mask',
                'data'         : {
                                  'dst_ip'      : '0.0.0.0/32',
                                 },
                'doc'          : 'vns|vns-access-list-cidr-range',
            }
        ),
        (
            {
                'token'  : 'any',
                'data'   : {
                              'src_ip'      : '0.0.0.0/32',
                              'dst_ip'      : '0.0.0.0/32',
                           },
                'doc'    : 'vns|vns-access-list-ip-any',
            }
        ),
    )
}

SRC_PORT_MATCH = (
    {
        'field'  : 'src_tp_port_op',
        'type'   : 'enum',
        'values' : ('eq', 'neq'),
        'doc'    : 'vns|vns-access-list-port-op-+',
    },
    {
        'choices' : (
            {
                'field'        : 'src_tp_port',
                'base-type'    : 'hex-or-decimal-integer',
                'range'        : (0,65535),
                'data-handler' : 'hex-to-integer',
                'doc'          : 'vns|vns-access-list-port-hex',
                'doc-include'  : [ 'range' ],
            },
            {
                'field'   : 'src_tp_port',
                'type'    : 'enum',
                'values'  : fmtcnv.tcp_name_to_number_dict,
                'permute' : 'skip',
                'doc'     : 'vns|vns-access-list-port-type',
            },
        ),
    },
)


DST_IP_MATCH = {
    'choices' : (
        (
            {
                'field'        : 'dst_ip',
                'type'         : 'cidr-range',
                'help-name'    : 'dst-cidr',
                #'data-handler' : 'split-cidr-data-inverse',
                #'dest-ip'      : 'dst-ip',
                #'dest-netmask' : 'dst-ip-mask',
                'doc'          : 'vns|vns-access-list-cidr-range',
            },
        ),
        (
            {
                'token'  : 'any',
                'data'   : {
                              'dst_ip'      : '0.0.0.0/32',
                           },
                'doc'    : 'vns|vns-access-list-ip-any',
            }
        ),
    )
}


DST_PORT_MATCH = (
    {
        'field' : 'dst_tp_port_op',
        'type'  : 'enum',
        'values' : ('eq', 'neq'),
        'doc'          : 'vns|vns-access-list-port-op+',
    },
    {
        'choices' : (
            {
                'field'        : 'dst_tp_port',
                'base-type'    : 'hex-or-decimal-integer',
                'range'        : (0,65535),
                'data-handler' : 'hex-to-integer',
                'doc'          : 'vns|vns-access-list-port-hex',
            },
            {
                'field'   : 'dst_tp_port',
                'type'    : 'enum',
                'values'  : fmtcnv.tcp_name_to_number_dict,
                'permute' : 'skip'
            },
        ),
    }
)

POLICY_FLOW_ENTRY_COMMAND_DESCRIPTION = {
    'name'            : 'flow-entry',
    'mode'            : 'config-policy',
    'command-type'    : 'config',
    'short-help'      : 'Configure flow entry',
    'doc'             : 'flow-entry|flow-entry',
    'doc-example'     : 'flow-entry|flow-entry-example',
    'parent-field'    : 'policy',
    'args' : {
        'action'       : (
                            {
                                'proc' : 'create-policy',
                            },
                         ),
        'choices' : (
            (
                {
                    'choices' : (
                        {
                            'field'  : 'proto_type',
                            'type'   : 'enum',
                            'values' : ('ip','tcp','udp'),
                            'doc'    : 'vns|vns-access-list-entry-type-+',
                        },
                        {
                            'field'        : 'proto_type',
                            'base-type'    : 'hex-or-decimal-integer',
                            'range'        : (0,255),
                            'help-name'    : 'ip protocol',
                            'data-handler' : 'hex-to-integer',
                            'doc'          : 'vns|vns-access-entry-type-ip-protocol',
                            'doc-include'  : [ 'range' ],
                        },
                    )
                },
                # Complexity arises from the SRC_IP match part 
                # being, required, while the port match
                # is optional, as is the DST_IP match, but the
                # DST_PORT_MATCH is only possible to describe when
                # the DST_IP part is included
                SRC_IP_MATCH,
                {
                    'optional' : True,
                    'optional-for-no' : True,
                    'args' : SRC_PORT_MATCH,
                },
                {
                    'optional' : True,
                    'optional-for-no' : True,
                    'args' : (
                        DST_IP_MATCH,
                        {
                            'optional' : True,
                            'optional-for-no' : True,
                            'args' : DST_PORT_MATCH,
                        },
                    ),
                },
            ),
        ),
    },
}

POLICY_TUNNEL_ID_COMMAND_DESCRIPTION = {
    'name'            : 'tunnel',
    'mode'            : 'config-policy',
    #'obj-type'        : 'policy-config',
    'command-type'    : 'config',
    'short-help'      : 'Configure tunnel id',
    #'doc'             : 'policy|tunnel',
    #'doc-example'     : 'policy|policy-tunnel-example',
    'parent-field'    : 'policy',
    'args' : {
        'action'       : (
                            {
                                'proc' : 'create-policy',
                            },
                         ),
        'field'        : 'tunnel-id',
        'type'         : 'identifier',
        'syntax-help'  : 'Enter tunnel id',
        'doc'          : 'policy|tunnel-id',
        'doc-include'  : [ 'type-doc' ],
    }
}

POLICY_PRIORITY_COMMAND_DESCRIPTION = {
    'name'            : 'priority',
    'mode'            : 'config-policy',
    'command-type'    : 'config',
    'short-help'      : 'Configure policy priority',
    'doc'             : 'policy|priority',
    'doc-example'     : 'policy|policy-priority-example',
    'parent-field'    : 'policy',
    'args' : {
        'action'       : (
                            {
                                'proc' : 'create-policy',
                            },
                         ),
        'field'     : 'priority',
        'base-type' : 'integer',
        'range'     : (0, 65535),
    }
}

SWITCH_TUNNEL_COMMAND_DESCRIPTION = {
    'name'                : 'show',
    'mode'                : 'login',
    'command-type'        : 'display-table',
    'all-help'            : 'Show switch information',
    'short-help'          : 'Show switch summary',
    #'obj-type'            : 'switches',
    'doc'                 : 'switch|show',
    'doc-example'         : 'switch|show-example',
    'args' : (
        {
            'token'  : 'policy',
            'field'  : 'showpolicy',
            'action' : 'display-rest',
            'doc'    : 'switch|show',
            'url'    : [
                        'showpolicy',
                       ],
            'format' : 'show_policy',
        },
    )
}