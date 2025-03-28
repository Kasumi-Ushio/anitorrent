%module(directors="1") anitorrent
%{
#include "anitorrent.hpp"
#include "events.hpp"
#include "peer_filter.hpp"
#include "session_t.hpp"
#include "torrent_add_info_t.hpp"
#include "torrent_handle_t.hpp"
#include "torrent_info_t.hpp"

%}

%include <std_string.i>  // Include support for std::string
%include <std_vector.i>  // Include support for std::vector

%feature("director") event_listener_t;
%feature("director") peer_filter_t;

%template(PeerInfoList) std::vector<anilt::peer_info_t>;
%template(CharVector) std::vector<char>;

%include stdint.i
%include "arrays_java.i"

%include "cpp/include/torrent_info_t.hpp"
%include "cpp/include/torrent_add_info_t.hpp"
%include "cpp/include/torrent_handle_t.hpp"
%include "cpp/include/events.hpp"
%include "cpp/include/peer_filter.hpp"
%include "cpp/include/session_t.hpp"
%include "cpp/include/anitorrent.hpp"

