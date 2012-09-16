package lila
package tournament

import org.joda.time.DateTime
import org.scala_tools.time.Imports._
import scalaz.effects._
import scalaz.{ NonEmptyList, Success, Failure }
import play.api.libs.json._

import game.{ DbGame, DbPlayer, GameRepo }
import user.User

final class TournamentApi(
    repo: TournamentRepo,
    gameRepo: GameRepo,
    timelinePush: DbGame ⇒ IO[Unit],
    getUser: String ⇒ IO[Option[User]],
    socket: Socket,
    siteSocket: site.Socket,
    lobbyNotify: String ⇒ IO[Unit]) {

  def makePairings(tour: Started, pairings: NonEmptyList[Pairing]): IO[Unit] =
    (tour addPairings pairings) |> { tour2 ⇒
      for {
        _ ← repo saveIO tour2
        games ← (pairings map makeGame(tour)).sequence
        _ ← (games map socket.notifyPairing).sequence
      } yield ()
    }

  def createTournament(setup: TournamentSetup, me: User): IO[Created] = for {
    withdrawIds ← repo withdraw me.id
    created = Tournament(
      createdBy = me,
      clock = TournamentClock(setup.clockTime * 60, setup.clockIncrement),
      minutes = setup.minutes,
      minPlayers = setup.minPlayers)
    _ ← repo saveIO created
    _ ← (withdrawIds map socket.reload).sequence
    _ ← reloadSiteSocket
    _ ← lobbyReload
  } yield created

  def start(created: Created): IO[Unit] = (for {
    _ ← repo saveIO created.start
    _ ← socket reload created.id
    _ ← reloadSiteSocket
    _ ← lobbyReload
  } yield ()) doIf created.readyToStart

  def wipeEmpty(created: Created): IO[Unit] = (for {
    _ ← repo removeIO created
    _ ← reloadSiteSocket
    _ ← lobbyReload
  } yield ()) doIf created.isEmpty

  def finish(started: Started): IO[Unit] = (for {
    _ ← repo saveIO started.finish
    _ ← socket reloadPage started.id
    _ ← reloadSiteSocket
  } yield ()) doIf started.readyToFinish

  def join(tour: Created, me: User): Valid[IO[Unit]] = for {
    tour2 ← tour join me
  } yield for {
    withdrawIds ← repo withdraw me.id
    _ ← repo saveIO tour2
    _ ← ((tour.id :: withdrawIds) map socket.reload).sequence
    _ ← reloadSiteSocket
    _ ← lobbyReload
  } yield ()

  def withdraw(tour: Tournament, userId: String): IO[Unit] = tour match {
    case created: Created ⇒ (created withdraw userId).fold(
      err ⇒ putStrLn(err.shows),
      tour ⇒ for {
        _ ← repo saveIO tour
        _ ← socket reload tour.id
        _ ← reloadSiteSocket
        _ ← lobbyReload
      } yield ()
    )
    case started: Started ⇒ (started withdraw userId).fold(
      err ⇒ putStrLn(err.shows),
      tour ⇒ tour.readyToFinish.fold(
        finish(tour),
        for {
          _ ← repo saveIO tour
          _ ← socket reload tour.id
          _ ← reloadSiteSocket
        } yield ()
      )
    )
    case finished: Finished ⇒ putStrLn("Cannot withdraw from finished tournament " + finished.id)
  }

  def finishGame(game: DbGame): IO[Option[Tournament]] = for {
    tourOption ← game.tournamentId.fold(repo.startedById, io(None))
    result ← tourOption.filter(_ ⇒ game.finished).fold(
      tour ⇒ repo saveIO tour.updatePairing(
        game.id, _.finish(game.status, game.winnerUserId)
      ) map (_ ⇒ tour.some),
      io(none)
    )
  } yield result

  private def lobbyReload = for {
    tours ← repo.created
    _ ← lobbyNotify(views.html.tournament.createdTable(tours).toString)
  } yield ()

  private val reloadMessage = JsObject(Seq("t" -> JsString("reload"), "d" -> JsNull))
  private def sendToSiteSocket(message: JsObject) = io {
    siteSocket.sendToFlag("tournament", message)
  }
  private val reloadSiteSocket = sendToSiteSocket(reloadMessage)

  private def makeGame(tour: Started)(pairing: Pairing): IO[DbGame] = for {
    user1 ← getUser(pairing.user1) map (_ err "No such user " + pairing)
    user2 ← getUser(pairing.user2) map (_ err "No such user " + pairing)
    variant = chess.Variant.Standard
    game = DbGame(
      game = chess.Game(
        board = chess.Board init variant,
        clock = tour.clock.chessClock.some
      ),
      ai = None,
      whitePlayer = DbPlayer.white withUser user1,
      blackPlayer = DbPlayer.black withUser user2,
      creatorColor = chess.Color.White,
      mode = chess.Mode.Casual,
      variant = variant
    ).withTournamentId(tour.id)
      .withId(pairing.gameId)
      .start
      .startClock(2)
    _ ← gameRepo insert game
    _ ← gameRepo denormalizeStarted game
    _ ← timelinePush(game)
  } yield game
}
